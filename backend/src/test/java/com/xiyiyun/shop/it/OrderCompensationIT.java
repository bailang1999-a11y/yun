package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.OrderCompensationGateway;
import com.xiyiyun.shop.mvp.OrderCompensationProperties;
import com.xiyiyun.shop.mvp.OrderCompensationService;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.SupplierBusinessException;
import com.xiyiyun.shop.mvp.SupplierTransportException;
import com.xiyiyun.shop.mvp.UpstreamOrderSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批次8A：补偿任务（超时关单 / 轮询未终结 / 对账）打在真实 MySQL 上。
 *
 * <h2>桩只打在上游那一跳</h2>
 * 测试包装 {@link com.xiyiyun.shop.mvp.InMemoryShopRepository#orderCompensationGateway()} 返回的
 * <b>真实网关</b>，只替换 {@link OrderCompensationGateway#fetchUpstreamOrderStatus}。
 * 因此状态 CAS、退款落账、库存归还、操作日志全部走生产代码 + 真实数据库，
 * 只有"问上游"这一次 HTTP 被替换成可控应答。若改成整体 mock 网关，
 * 这些用例就只能证明"service 调了方法"，而证明不了钱和库存真的对。
 *
 * <h2>最重要的用例</h2>
 * {@link #transportFailureMustNotJudgeOrderFailed()}：上游查单超时 ⇒ 结果未知 ⇒
 * 订单状态不变、不退款。这是缺陷 A4 的核心不变量，任何"查不到就判失败"的回退
 * 都会让这条用例变红。上游可能已经受理并扣了我方预付款，判失败等于双向资损。
 */
class OrderCompensationIT extends AbstractIntegrationTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("500.0000");
    private static final BigDecimal PRICE = new BigDecimal("20.0000");
    private static final long DIRECT_GOODS_ID = 8101L;
    private static final int INITIAL_STOCK = 10;

    /** 三个时间阈值压到 1 分钟，测试只需把订单时间往前拨几分钟即可命中。 */
    private static final OrderCompensationProperties FAST = OrderCompensationProperties.defaults()
        .withAges(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1));

    /** 上游应答桩：key = 订单号，value = 该单被查询时的行为。 */
    private final Map<String, Function<OrderItem, Optional<UpstreamOrderSnapshot>>> upstreamStubs = new HashMap<>();
    private final Set<String> callbackSentOrders = new HashSet<>();

    /**
     * 只替换 fetchUpstreamOrderStatus 的委托网关。
     *
     * <p>没有登记桩的订单返回 {@code empty()}（= 无法查询），与生产里"没有可查渠道"同义，
     * 补偿任务应当跳过而不是判定终态。
     */
    private OrderCompensationService serviceWithStubbedUpstream() {
        OrderCompensationGateway real = repository.orderCompensationGateway();
        OrderCompensationGateway stubbed = new OrderCompensationGateway() {
            @Override
            public List<OrderItem> orderSnapshots() {
                return real.orderSnapshots();
            }

            @Override
            public List<OrderItem> unsettledOrderCandidates(
                OffsetDateTime deadline,
                int limit,
                boolean withoutCallback
            ) {
                return real.unsettledOrderCandidates(deadline, limit, withoutCallback);
            }

            @Override
            public boolean fundsLedgerEnabled() {
                return real.fundsLedgerEnabled();
            }

            @Override
            public String currentOrderStatus(String orderNo) {
                return real.currentOrderStatus(orderNo);
            }

            @Override
            public boolean compareAndSetOrderStatus(String orderNo, OrderStatus expected, OrderStatus next) {
                return real.compareAndSetOrderStatus(orderNo, expected, next);
            }

            @Override
            public boolean compareAndSetOrderStatusFromEither(
                String orderNo, OrderStatus expectedA, OrderStatus expectedB, OrderStatus next
            ) {
                return real.compareAndSetOrderStatusFromEither(orderNo, expectedA, expectedB, next);
            }

            @Override
            public void restoreReservedStock(OrderItem order) {
                real.restoreReservedStock(order);
            }

            @Override
            public void refundToBalance(OrderItem order, String reason) {
                real.refundToBalance(order, reason);
            }

            @Override
            public void saveAndPublishOrder(OrderItem order) {
                real.saveAndPublishOrder(order);
            }

            @Override
            public Optional<UpstreamOrderSnapshot> fetchUpstreamOrderStatus(OrderItem order) {
                return upstreamStubs
                    .getOrDefault(order.orderNo(), any -> Optional.empty())
                    .apply(order);
            }

            @Override
            public boolean callbackSentOnSubmit(OrderItem order) {
                return callbackSentOrders.contains(order.orderNo());
            }

            @Override
            public OrderItem applyUpstreamSnapshot(
                OrderItem order, UpstreamOrderSnapshot upstream, OrderStatus nextStatus
            ) {
                return real.applyUpstreamSnapshot(order, upstream, nextStatus);
            }

            @Override
            public void recordAudit(String action, String resourceType, String resourceId, String remark) {
                real.recordAudit(action, resourceType, resourceId, remark);
            }
        };
        return new OrderCompensationService(stubbed, FAST);
    }

    // ---------------------------------------------------------------- fixtures

    /** 用户组 + 分类 + 用户 + 一个有库存的直充商品。 */
    private void seedDirectGoodsScenario() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(INITIAL_BALANCE);
        fixtures.insertGoods(DIRECT_GOODS_ID, "IT直充商品", "DIRECT", INITIAL_STOCK, PRICE, null);
    }

    private String insertDirectOrder(String orderNo, String status, int quantity) {
        fixtures.insertOrder(orderNo, ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", status, quantity, PRICE);
        return orderNo;
    }

    /**
     * 把订单时间往前拨，命中补偿任务的最小停留时长。
     *
     * <p>用 SQL 直接改时间而不是 sleep：既不拖慢测试，也能精确控制"刚好没到阈值"的对照单。
     */
    private void backdateOrder(String orderNo, Duration age, boolean setPaidAt) {
        OffsetDateTime moment = OffsetDateTime.now().minus(age);
        jdbcTemplate.update("UPDATE orders SET created_at = ?" + (setPaidAt ? ", paid_at = ?" : "")
                + " WHERE order_no = ?",
            setPaidAt ? new Object[] {moment, moment, orderNo} : new Object[] {moment, orderNo});
    }

    private void markCallbackSent(String orderNo) {
        jdbcTemplate.update(
            "UPDATE orders SET channel_attempts_json = ? WHERE order_no = ?",
            "[{\"supplierId\":20004,\"status\":\"PROCURING\","
                + "\"callbackUrl\":\"https://api.xiyi.co/api/upstream/jingzhao/callback/20004\"}]",
            orderNo
        );
    }

    private String orderStatus(String orderNo) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE order_no = ?", String.class, orderNo);
    }

    private int countRefundLedger(String orderNo) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_balance_transactions WHERE biz_type = 'ORDER_REFUND' AND biz_no = ?",
            Integer.class, orderNo);
    }

    private int countAuditLogs(String action, String orderNo) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM admin_operation_logs WHERE action = ? AND resource_id = ?",
            Integer.class, action, orderNo);
    }

    private static UpstreamOrderSnapshot deliveredSnapshot(String upstreamOrderNo) {
        return new UpstreamOrderSnapshot(
            upstreamOrderNo, "", OrderStatus.DELIVERED, "SUCCESS", "上游已发货",
            "", PRICE, "IT直充商品", List.of(), "{\"status\":\"success\"}");
    }

    private static UpstreamOrderSnapshot processingSnapshot() {
        return new UpstreamOrderSnapshot(
            "UP-PROCESSING", "", OrderStatus.PROCURING, "PROCESSING", "上游仍在处理",
            "", PRICE, "IT直充商品", List.of(), "{\"status\":\"processing\"}");
    }

    // ---------------------------------------------------------------- 任务1：超时关单

    @Test
    @DisplayName("超时关单：过期未支付订单必须取消并归还预扣库存，未到期的不得动")
    void timedOutUnpaidOrderMustBeCancelledAndStockRestored() {
        seedDirectGoodsScenario();
        String expired = insertDirectOrder("ITCOMP-TIMEOUT-1", "UNPAID", 2);
        String fresh = insertDirectOrder("ITCOMP-TIMEOUT-2", "UNPAID", 3);
        backdateOrder(expired, Duration.ofMinutes(30), false);
        // 对照单只放置 5 秒，远没到 1 分钟阈值
        backdateOrder(fresh, Duration.ofSeconds(5), false);

        assertThat(fixtures.goodsStock(DIRECT_GOODS_ID))
            .as("前置条件：库存为下单预扣后的剩余值")
            .isEqualTo(INITIAL_STOCK);

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().closeTimedOutOrders();

        assertThat(result.changed()).as("只有超时那一笔该被关掉").isEqualTo(1);
        assertThat(orderStatus(expired))
            .as("超时未支付订单必须自动取消，否则预扣库存永久悬空")
            .isEqualTo("CANCELLED");
        assertThat(orderStatus(fresh))
            .as("未到支付时限的订单不得被补偿任务取消")
            .isEqualTo("UNPAID");
        assertThat(fixtures.goodsStock(DIRECT_GOODS_ID))
            .as("取消必须归还该单预扣的 2 件库存")
            .isEqualTo(INITIAL_STOCK + 2);
        assertThat(countAuditLogs("ORDER_TIMEOUT_CLOSED", expired))
            .as("关单要留操作日志")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("超时关单幂等：重复执行不得二次归还库存")
    void repeatedCloseMustNotRestoreStockTwice() {
        seedDirectGoodsScenario();
        String expired = insertDirectOrder("ITCOMP-TIMEOUT-3", "UNPAID", 2);
        backdateOrder(expired, Duration.ofMinutes(30), false);

        OrderCompensationService service = serviceWithStubbedUpstream();
        service.closeTimedOutOrders();
        int stockAfterFirst = fixtures.goodsStock(DIRECT_GOODS_ID);
        service.closeTimedOutOrders();

        assertThat(fixtures.goodsStock(DIRECT_GOODS_ID))
            .as("库存归还只在 CAS 赢下之后发生，第二轮必须是空操作")
            .isEqualTo(stockAfterFirst)
            .isEqualTo(INITIAL_STOCK + 2);
    }

    // ---------------------------------------------------------------- 任务2：轮询未终结订单

    @Test
    @DisplayName("快速轮询：支付满 10 秒且未向上游发送回调地址的订单必须真实查单")
    void fastPollMustQueryOnlyOrdersWithoutCallbackAfterTenSeconds() {
        seedDirectGoodsScenario();
        String due = insertDirectOrder("ITCOMP-FAST-1", "PROCURING", 1);
        String fresh = insertDirectOrder("ITCOMP-FAST-2", "PROCURING", 1);
        String callback = insertDirectOrder("ITCOMP-FAST-3", "PROCURING", 1);
        backdateOrder(due, Duration.ofSeconds(20), true);
        backdateOrder(fresh, Duration.ofSeconds(5), true);
        backdateOrder(callback, Duration.ofSeconds(20), true);
        markCallbackSent(callback);
        callbackSentOrders.add(callback);
        upstreamStubs.put(due, order -> Optional.of(deliveredSnapshot("UP-FAST-1")));
        upstreamStubs.put(fresh, order -> Optional.of(deliveredSnapshot("UP-FAST-2")));
        upstreamStubs.put(callback, order -> Optional.of(deliveredSnapshot("UP-FAST-3")));

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream()
            .pollUnsettledOrdersWithoutCallback();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(orderStatus(due)).isEqualTo("DELIVERED");
        assertThat(orderStatus(fresh)).isEqualTo("PROCURING");
        assertThat(orderStatus(callback)).isEqualTo("PROCURING");
    }

    @Test
    @DisplayName("快速轮询候选：数据库必须完成类型、状态、时间、回调和批次上限过滤")
    void fastPollCandidatesMustBeFilteredAndLimitedInDatabase() {
        seedDirectGoodsScenario();
        String oldest = insertDirectOrder("ITCOMP-CAND-1", "PROCURING", 1);
        String next = insertDirectOrder("ITCOMP-CAND-2", "DELIVERING", 1);
        String overflow = insertDirectOrder("ITCOMP-CAND-3", "PROCURING", 1);
        String fresh = insertDirectOrder("ITCOMP-CAND-4", "PROCURING", 1);
        String callback = insertDirectOrder("ITCOMP-CAND-5", "PROCURING", 1);
        String terminal = insertDirectOrder("ITCOMP-CAND-6", "DELIVERED", 1);
        long cardGoodsId = DIRECT_GOODS_ID + 1;
        fixtures.insertGoods(cardGoodsId, "IT卡密商品", "CARD", INITIAL_STOCK, PRICE, null);
        fixtures.insertOrder("ITCOMP-CAND-7", ItFixtures.USER_ID, cardGoodsId, "CARD", "PROCURING", 1, PRICE);
        backdateOrder(oldest, Duration.ofSeconds(40), true);
        backdateOrder(next, Duration.ofSeconds(30), true);
        backdateOrder(overflow, Duration.ofSeconds(20), true);
        backdateOrder(fresh, Duration.ofSeconds(5), true);
        backdateOrder(callback, Duration.ofSeconds(50), true);
        backdateOrder(terminal, Duration.ofSeconds(60), true);
        backdateOrder("ITCOMP-CAND-7", Duration.ofSeconds(70), true);
        markCallbackSent(callback);

        List<OrderItem> candidates = repository.orderCompensationGateway()
            .unsettledOrderCandidates(OffsetDateTime.now().minusSeconds(10), 2, true);

        assertThat(candidates).extracting(OrderItem::orderNo)
            .containsExactly(oldest, next);
    }

    @Test
    @DisplayName("轮询：上游确认已发货的卡单订单必须推进到 DELIVERED 并落上游单号")
    void upstreamDeliveredMustAdvanceOrderToDelivered() {
        seedDirectGoodsScenario();
        String stuck = insertDirectOrder("ITCOMP-POLL-1", "PROCURING", 1);
        backdateOrder(stuck, Duration.ofMinutes(20), true);
        upstreamStubs.put(stuck, order -> Optional.of(deliveredSnapshot("UP-9527")));

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().pollUnsettledOrders();

        assertThat(result.changed()).isEqualTo(1);
        assertThat(orderStatus(stuck))
            .as("上游明确已发货，卡住的订单必须收尾成 DELIVERED")
            .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT upstream_order_no FROM orders WHERE order_no = ?", String.class, stuck))
            .as("上游订单号要落库，供人工对账")
            .isEqualTo("UP-9527");
        assertThat(countRefundLedger(stuck))
            .as("发货成功的订单不得产生退款流水")
            .isZero();
    }

    /**
     * 缺陷 A4 的回归护栏。
     *
     * <p>上游查单超时 = 结果未知：上游<b>可能</b>已经受理并扣了我方预付款。
     * 此时判 FAILED 并退款，会同时损失预付款与退给用户的钱。
     * 因此唯一正确的处理是什么都不做、等下一轮。
     */
    @Test
    @DisplayName("轮询·A4 护栏：上游查单超时（结果未知）必须状态不变、不退款")
    void transportFailureMustNotJudgeOrderFailed() {
        seedDirectGoodsScenario();
        String unknown = insertDirectOrder("ITCOMP-POLL-2", "PROCURING", 1);
        backdateOrder(unknown, Duration.ofMinutes(20), true);
        upstreamStubs.put(unknown, order -> {
            throw new SupplierTransportException("IT-SUPPLIER", "queryOrder", "read timed out");
        });

        BigDecimal balanceBefore = fixtures.userBalance(ItFixtures.USER_ID);
        int stockBefore = fixtures.goodsStock(DIRECT_GOODS_ID);

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().pollUnsettledOrders();

        assertThat(result.changed())
            .as("结果未知的订单不得被计为已变更")
            .isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed())
            .as("上游超时是预期分支，不该算作任务失败")
            .isZero();
        assertThat(orderStatus(unknown))
            .as("上游查单超时属结果未知，绝不能判 FAILED/REFUNDED —— 上游可能已受理并扣了预付款")
            .isEqualTo("PROCURING");
        assertThat(countRefundLedger(unknown))
            .as("结果未知时退款就是资损，退款流水必须为 0")
            .isZero();
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("结果未知不得改动用户余额")
            .isEqualByComparingTo(balanceBefore);
        assertThat(fixtures.goodsStock(DIRECT_GOODS_ID))
            .as("结果未知不得归还库存")
            .isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("轮询：上游明确应答未受理才可判失败退款，且重复执行只退一次")
    void upstreamExplicitRejectionMustRefundExactlyOnce() {
        seedDirectGoodsScenario();
        String rejected = insertDirectOrder("ITCOMP-POLL-3", "PROCURING", 1);
        backdateOrder(rejected, Duration.ofMinutes(20), true);
        upstreamStubs.put(rejected, order -> {
            throw new SupplierBusinessException("IT-SUPPLIER", "queryOrder", "ORDER_NOT_FOUND", "订单不存在");
        });

        BigDecimal balanceBefore = fixtures.userBalance(ItFixtures.USER_ID);
        OrderCompensationService service = serviceWithStubbedUpstream();
        service.pollUnsettledOrders();

        assertThat(orderStatus(rejected))
            .as("上游明确说没这笔单，资金结果确定，可以判退款")
            .isEqualTo("REFUNDED");
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("退款必须真的加回余额")
            .isEqualByComparingTo(balanceBefore.add(PRICE));
        assertThat(countRefundLedger(rejected)).isEqualTo(1);

        // 第二轮：订单已是 REFUNDED，CAS 抢不到，且幂等键兜底
        service.pollUnsettledOrders();
        assertThat(countRefundLedger(rejected))
            .as("重复执行不得二次退款（uk_balance_tx_biz 幂等键）")
            .isEqualTo(1);
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("重复执行后余额不得再变")
            .isEqualByComparingTo(balanceBefore.add(PRICE));
    }

    @Test
    @DisplayName("轮询：上游仍在处理中的订单保持中间态，不做任何终态判定")
    void upstreamStillProcessingMustKeepIntermediateStatus() {
        seedDirectGoodsScenario();
        String processing = insertDirectOrder("ITCOMP-POLL-4", "PROCURING", 1);
        backdateOrder(processing, Duration.ofMinutes(20), true);
        upstreamStubs.put(processing, order -> Optional.of(processingSnapshot()));

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().pollUnsettledOrders();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(orderStatus(processing)).isEqualTo("PROCURING");
        assertThat(countRefundLedger(processing)).isZero();
    }

    /**
     * 上游单号落库的回归护栏。
     *
     * <p>本用例写于修复一处真实缺陷之后：{@code OrderRecordMapper} 的 upsert SQL
     * 与两条 SELECT 都<b>整列漏掉</b> upstream_order_no，导致 006 建的
     * uk_orders_upstream（防重复采购）永远不可能触发，A4 的采购凭据也全部丢失。
     *
     * <p>同时断言 write-once：已落库的上游单号不得被后续快照覆盖。
     */
    @Test
    @DisplayName("上游单号：必须真的落库，且落库后不被后续快照覆盖（write-once）")
    void upstreamOrderNoMustPersistAndStayImmutable() {
        seedDirectGoodsScenario();
        String order = insertDirectOrder("ITCOMP-UPNO-1", "PROCURING", 1);
        backdateOrder(order, Duration.ofMinutes(20), true);
        upstreamStubs.put(order, ignored -> Optional.of(deliveredSnapshot("UP-FIRST")));

        serviceWithStubbedUpstream().pollUnsettledOrders();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT upstream_order_no FROM orders WHERE order_no = ?", String.class, order))
            .as("上游单号必须真的写进 orders.upstream_order_no，否则 uk_orders_upstream 形同虚设")
            .isEqualTo("UP-FIRST");

        // 再保存一次带不同上游单号的快照：write-once 必须保住第一次的值
        OrderItem reloaded = repository.orderCompensationGateway().orderSnapshots().stream()
            .filter(item -> item.orderNo().equals(order))
            .findFirst()
            .orElseThrow();
        repository.orderCompensationGateway()
            .saveAndPublishOrder(reloaded.withUpstreamOrderNo("UP-SECOND"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT upstream_order_no FROM orders WHERE order_no = ?", String.class, order))
            .as("采购凭据一经落库不得被覆盖，否则重试/对账会冲掉真实上游单号")
            .isEqualTo("UP-FIRST");
    }

    @Test
    @DisplayName("上游单号冲突：撞 uk_orders_upstream 不得改写另一笔订单的数据")
    void upstreamOrderNoConflictMustNotOverwriteAnotherOrder() {
        seedDirectGoodsScenario();
        String owner = insertDirectOrder("ITCOMP-UPNO-2", "PROCURING", 1);
        String intruder = insertDirectOrder("ITCOMP-UPNO-3", "PROCURING", 2);
        backdateOrder(owner, Duration.ofMinutes(20), true);
        backdateOrder(intruder, Duration.ofMinutes(20), true);
        upstreamStubs.put(owner, ignored -> Optional.of(deliveredSnapshot("UP-SHARED")));
        upstreamStubs.put(intruder, ignored -> Optional.of(deliveredSnapshot("UP-SHARED")));

        serviceWithStubbedUpstream().pollUnsettledOrders();

        // 第一笔正常占用该上游单号
        assertThat(jdbcTemplate.queryForObject(
            "SELECT upstream_order_no FROM orders WHERE order_no = ?", String.class, owner))
            .isEqualTo("UP-SHARED");
        // 第二笔撞唯一索引：绝不能把 owner 那一行改写成 intruder 的数据（ODKU 串单）
        assertThat(jdbcTemplate.queryForObject(
            "SELECT quantity FROM orders WHERE order_no = ?", Integer.class, owner))
            .as("上游单号冲突不得让另一笔订单的字段被串改")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE order_no IN (?, ?)", Integer.class, owner, intruder))
            .as("两笔订单都必须还在，冲突不得吞掉任何一行")
            .isEqualTo(2);
    }

    // ---------------------------------------------------------------- 任务3：对账（只报不改）

    @Test
    @DisplayName("对账：差异只写操作日志，绝不自动修改订单、余额、库存")
    void reconcileMustReportDiffWithoutMutatingData() {
        seedDirectGoodsScenario();
        String diverged = insertDirectOrder("ITCOMP-RECON-1", "PROCURING", 1);
        backdateOrder(diverged, Duration.ofMinutes(40), true);
        // 上游说已发货，本地还是 PROCURING —— 这就是一条要人工看的差异
        upstreamStubs.put(diverged, order -> Optional.of(deliveredSnapshot("UP-RECON-1")));

        BigDecimal balanceBefore = fixtures.userBalance(ItFixtures.USER_ID);
        int stockBefore = fixtures.goodsStock(DIRECT_GOODS_ID);

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().reconcileUnsettledOrders();

        assertThat(result.changed()).as("对账的 changed 计的是差异条数").isEqualTo(1);
        assertThat(countAuditLogs("ORDER_RECONCILE_DIFF", diverged))
            .as("差异必须留下可人工处理的操作日志")
            .isEqualTo(1);
        assertThat(orderStatus(diverged))
            .as("对账只报不改：订单状态必须原样保留")
            .isEqualTo("PROCURING");
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("对账不得动余额")
            .isEqualByComparingTo(balanceBefore);
        assertThat(fixtures.goodsStock(DIRECT_GOODS_ID))
            .as("对账不得动库存")
            .isEqualTo(stockBefore);
        assertThat(countRefundLedger(diverged))
            .as("对账不得产生退款流水")
            .isZero();
    }

    @Test
    @DisplayName("对账：本地与上游一致时不产生噪音日志")
    void reconcileMustStaySilentWhenStatusesMatch() {
        seedDirectGoodsScenario();
        String aligned = insertDirectOrder("ITCOMP-RECON-2", "PROCURING", 1);
        backdateOrder(aligned, Duration.ofMinutes(40), true);
        upstreamStubs.put(aligned, order -> Optional.of(processingSnapshot()));

        OrderCompensationService.CompensationResult result = serviceWithStubbedUpstream().reconcileUnsettledOrders();

        assertThat(result.changed()).isZero();
        assertThat(countAuditLogs("ORDER_RECONCILE_DIFF", aligned))
            .as("状态一致不该刷差异日志，否则真差异会被噪音淹没")
            .isZero();
    }
}
