package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批次8A：中间态订单的补偿收尾。
 *
 * <h2>为什么必须有这一层</h2>
 * 批次3 修缺陷 A4 之后，上游超时/连接失败/5xx/无法解析的订单转入中间态 {@code PROCURING}
 * 而不是 FAILED（上游可能已受理并扣了我方预付款）。但在此之前<b>没有任何东西去收尾这些订单</b>：
 * <ul>
 *   <li>上游已发货、回调丢了 → 订单永远卡 PROCURING，用户既拿不到货也拿不到退款；</li>
 *   <li>上游其实没受理 → 订单永远卡着，库存与预付款一起悬空；</li>
 *   <li>下单未支付 → UNPAID 订单永远占着下单即预扣的库存。</li>
 * </ul>
 * 这三条都是真实资金风险，对应本类的三个方法。
 *
 * <h2>A4 不变量（本类最重要的一条）</h2>
 * 轮询时<b>上游查单本身也可能超时</b>。这时 {@link SupplierTransportException} 的处理是
 * <b>什么都不做</b>（见 {@link #pollUnsettledOrders()} 里的 catch 分支），等下一轮再问。
 * 绝不能因为「查不到」就判 FAILED —— 那正是 A4 的原始缺陷。只有
 * {@link SupplierBusinessException}（上游明确说这笔单不存在/被拒）才允许判失败并退款。
 *
 * <h2>多实例安全 / 重复执行无害</h2>
 * 本类不依赖「只有一个实例在跑」这个假设：
 * <ol>
 *   <li>每一次状态流转都先走 DB 条件 UPDATE（CAS），<b>按受影响行数判定</b>。抢不到的实例
 *       直接跳过这笔单，不会先读后写覆盖别人的结果。</li>
 *   <li>退款走 {@code FundsLedgerStore.refundToBalance}，幂等键
 *       {@code uk_balance_tx_biz (ORDER_REFUND, orderNo)} 保证同一笔单只会真退一次。</li>
 *   <li>库存归还只在 CAS 赢下 CANCELLED 之后执行，因此一笔单最多归还一次。</li>
 * </ol>
 *
 * <h2>毒丸隔离与限量</h2>
 * 每笔订单独立 try/catch，一笔失败只记日志、继续下一笔；每轮都按配置的上限截断，
 * 避免积压时一轮几万笔把数据库和上游打死。
 *
 * <h2>不在锁里做 HTTP</h2>
 * 上游查单经 {@link OrderCompensationGateway#fetchUpstreamOrderStatus} 直接调适配器，
 * 本类不持有任何 JVM 锁；落库/推送在 HTTP 返回之后才发生。
 */
public class OrderCompensationService {
    private static final Logger log = LoggerFactory.getLogger(OrderCompensationService.class);
    private static final java.time.Duration FAST_POLL_MIN_AGE = java.time.Duration.ofSeconds(10);

    /** 支付超时可关的状态，与 {@link OrderStateMachine#canExpirePayment} 一致。 */
    private static final Set<OrderStatus> CLOSEABLE = Set.of(OrderStatus.CREATED, OrderStatus.UNPAID);

    private final OrderCompensationGateway gateway;
    private final OrderCompensationProperties properties;

    public OrderCompensationService(OrderCompensationGateway gateway, OrderCompensationProperties properties) {
        this.gateway = gateway;
        this.properties = properties == null ? OrderCompensationProperties.defaults() : properties;
    }

    /**
     * 一轮补偿的结果。
     *
     * @param scanned   命中筛选条件的候选笔数（未截断前）
     * @param processed 本轮实际处理的笔数（受单轮上限限制）
     * @param changed   真正发生了状态/数据变更的笔数
     * @param skipped   刻意跳过的笔数（结果未知、无查单能力、CAS 没抢到等）
     * @param failed    抛异常的笔数
     */
    public record CompensationResult(int scanned, int processed, int changed, int skipped, int failed) {
        static CompensationResult none() {
            return new CompensationResult(0, 0, 0, 0, 0);
        }
    }

    public OrderCompensationProperties properties() {
        return properties;
    }

    // ================================================================ 任务1：超时关单

    /**
     * UNPAID/CREATED 且超过支付时限的订单 → 取消 + 归还预扣库存。
     *
     * <p>状态流转走 {@code CREATED|UNPAID → CANCELLED} 的双前置 CAS：与支付侧
     * {@code CREATED|UNPAID → PAYING} 抢同一行的行锁，数据库保证不会出现
     * 「既被支付又被超时关单」。抢不到就说明用户正好付上了，本轮跳过。
     */
    public CompensationResult closeTimedOutOrders() {
        if (!gateway.fundsLedgerEnabled()) {
            // 纯内存模式没有条件 UPDATE 可用，补偿任务宁可不动手，也不退回「先读后写」
            return CompensationResult.none();
        }
        OffsetDateTime deadline = OffsetDateTime.now().minus(properties.paymentTimeout());
        List<OrderItem> candidates = gateway.orderSnapshots().stream()
            .filter(order -> order != null && CLOSEABLE.contains(order.status()))
            .filter(order -> order.createdAt() != null && order.createdAt().isBefore(deadline))
            .sorted(Comparator.comparing(OrderItem::createdAt))
            .toList();

        int changed = 0;
        int skipped = 0;
        int failed = 0;
        int processed = 0;
        for (OrderItem order : limited(candidates, properties.closeBatchLimit())) {
            processed++;
            try {
                boolean won = gateway.compareAndSetOrderStatusFromEither(
                    order.orderNo(), OrderStatus.CREATED, OrderStatus.UNPAID, OrderStatus.CANCELLED);
                if (!won) {
                    // 别人（用户支付 / 另一个实例 / 手工取消）已经改掉了这一行，本轮什么都不做
                    skipped++;
                    continue;
                }
                OrderItem cancelled = order.withStatus(
                    OrderStatus.CANCELLED, "订单支付超时，已自动取消", order.deliveredAt());
                gateway.saveAndPublishOrder(cancelled);
                // 归还库存只在 CAS 赢下之后做，所以一笔单最多归还一次
                gateway.restoreReservedStock(cancelled);
                gateway.recordAudit("ORDER_TIMEOUT_CLOSED", "ORDER", order.orderNo(),
                    "支付超时自动取消并归还预扣库存，超时阈值=" + properties.paymentTimeout());
                changed++;
            } catch (RuntimeException ex) {
                // 毒丸隔离：一笔失败绝不能带走整轮，否则补偿会永久停摆
                failed++;
                log.warn("compensation close timed-out order {} failed: {}", order.orderNo(), ex.toString());
            }
        }
        return new CompensationResult(candidates.size(), processed, changed, skipped, failed);
    }

    // ================================================================ 任务2：轮询未终结订单

    /**
     * PROCURING/DELIVERING 停留超过阈值的订单 → 问上游真实状态，据此推进到终态。
     *
     * <p>三种上游应答对应三种处理，<b>差别就是 A4 的全部内容</b>：
     * <ul>
     *   <li>明确终态（已发货 / 明确失败）→ CAS 推进到 DELIVERED / REFUNDED；</li>
     *   <li>{@link SupplierTransportException}（超时/连接失败/5xx/无法解析）→
     *       <b>什么都不做</b>，等下一轮。见下方 catch 分支；</li>
     *   <li>{@link SupplierBusinessException}（上游明确说没有这笔单）→ 判失败并退款。</li>
     * </ul>
     * 没有查单能力的上游（适配器 default 方法抛 {@link UnsupportedOperationException}）
     * 只跳过并记日志，不会让整轮任务挂掉。
     */
    public CompensationResult pollUnsettledOrders() {
        if (!gateway.fundsLedgerEnabled()) {
            return CompensationResult.none();
        }
        return pollCandidates(unsettledCandidates(
            properties.unsettledMinAge(), properties.pollBatchLimit(), false));
    }

    /** 没有把回调地址交给上游的订单，从支付后第 10 秒起进入快速真实查单。 */
    public CompensationResult pollUnsettledOrdersWithoutCallback() {
        if (!gateway.fundsLedgerEnabled()) {
            return CompensationResult.none();
        }
        List<OrderItem> candidates = unsettledCandidates(
            FAST_POLL_MIN_AGE, properties.pollBatchLimit(), true);
        return pollCandidates(candidates);
    }

    private CompensationResult pollCandidates(List<OrderItem> candidates) {
        int changed = 0;
        int skipped = 0;
        int failed = 0;
        int processed = 0;
        for (OrderItem order : limited(candidates, properties.pollBatchLimit())) {
            processed++;
            try {
                // HTTP 在这里发生，本方法不持有任何 JVM 锁
                Optional<UpstreamOrderSnapshot> fetched = gateway.fetchUpstreamOrderStatus(order);
                if (fetched.isEmpty()) {
                    // 没有可查渠道 / 供应商缺失 / 占位地址：本轮跳过，不判定任何终态
                    skipped++;
                    continue;
                }
                UpstreamOrderSnapshot upstream = fetched.get();
                OrderStatus resolved = upstream.resolvedLocalStatus(order.status());
                if (resolved == OrderStatus.DELIVERED) {
                    changed += settleDelivered(order, upstream) ? 1 : 0;
                } else if (resolved == OrderStatus.FAILED) {
                    changed += settleFailed(order, upstream, upstream.deliveryMessage()) ? 1 : 0;
                } else {
                    // 上游仍在处理中：保持中间态，等下一轮
                    skipped++;
                }
            } catch (SupplierTransportException ex) {
                // ★ A4 不变量：上游查单结果未知 —— 什么都不做，不判 FAILED、不退款、不改状态。
                //   上游可能已经受理并扣了我方预付款，任何"查不到就判失败"都会造成双向资损。
                skipped++;
                log.info("compensation poll: upstream result unknown for order {} ({}), leaving status unchanged",
                    order.orderNo(), ex.getMessage());
            } catch (SupplierBusinessException ex) {
                // 上游明确应答「没有这笔单 / 已拒单」：不存在资金未知，可以判失败并退款
                try {
                    changed += settleFailed(order, null, "上游明确应答该单未受理：" + ex.getMessage()) ? 1 : 0;
                } catch (RuntimeException nested) {
                    failed++;
                    log.warn("compensation poll: settle failed order {} error: {}", order.orderNo(), nested.toString());
                }
            } catch (UnsupportedOperationException ex) {
                // 该上游没有查单能力（capability bit 未实现）：跳过并记日志，不让整轮挂掉
                skipped++;
                log.info("compensation poll: supplier has no order-query capability for order {}: {}",
                    order.orderNo(), ex.getMessage());
            } catch (RuntimeException ex) {
                failed++;
                log.warn("compensation poll order {} failed: {}", order.orderNo(), ex.toString());
            }
        }
        return new CompensationResult(candidates.size(), processed, changed, skipped, failed);
    }

    /** 上游已发货：CAS 推进到 DELIVERED，再补齐卡密/交付项等描述字段。 */
    private boolean settleDelivered(OrderItem order, UpstreamOrderSnapshot upstream) {
        boolean won = gateway.compareAndSetOrderStatusFromEither(
            order.orderNo(), OrderStatus.PROCURING, OrderStatus.DELIVERING, OrderStatus.DELIVERED);
        if (!won) {
            return false;
        }
        OrderItem next = gateway.applyUpstreamSnapshot(order, upstream, OrderStatus.DELIVERED);
        gateway.saveAndPublishOrder(next);
        gateway.recordAudit("ORDER_COMPENSATION_DELIVERED", "ORDER", order.orderNo(),
            "补偿轮询：上游确认已发货，订单推进到 DELIVERED");
        return true;
    }

    /**
     * 上游明确未受理：CAS 推进到 REFUNDED 再退款。
     *
     * <p>顺序刻意是「先 CAS 后退款」：CAS 赢下才代表本实例拿到了这笔单的处置权；
     * 退款本身还有幂等键兜底，所以即使退款前后进程挂掉，重跑也不会重复退钱。
     */
    private boolean settleFailed(OrderItem order, UpstreamOrderSnapshot upstream, String reason) {
        boolean won = gateway.compareAndSetOrderStatusFromEither(
            order.orderNo(), OrderStatus.PROCURING, OrderStatus.DELIVERING, OrderStatus.REFUNDED);
        if (!won) {
            return false;
        }
        OrderItem next = upstream == null
            ? order.withStatus(OrderStatus.REFUNDED, defaultText(reason, "上游确认未受理，已退款"), order.deliveredAt())
            : gateway.applyUpstreamSnapshot(order, upstream, OrderStatus.REFUNDED);
        gateway.saveAndPublishOrder(next);
        if (order.payAmount() != null && order.payAmount().compareTo(BigDecimal.ZERO) > 0) {
            // 幂等键 uk_balance_tx_biz (ORDER_REFUND, orderNo)：重复执行只会真退一次
            gateway.refundToBalance(order, defaultText(reason, "补偿退款：上游确认未受理"));
        }
        gateway.restoreReservedStock(order);
        gateway.recordAudit("ORDER_COMPENSATION_REFUNDED", "ORDER", order.orderNo(),
            "补偿轮询：" + defaultText(reason, "上游确认未受理") + "，已退款并归还库存");
        return true;
    }

    // ================================================================ 任务3：对账（只报不改）

    /**
     * 拉上游状态与本地比对，<b>把差异写进操作日志，绝不自动改数据</b>。
     *
     * <p>这是刻意的保守设计：对账自动改数据的风险太高（上游状态口径、时序、
     * 部分发货都可能让"自动纠正"制造出更大的资金差错），第一版只产出差异报告供人工处理。
     * 因此本方法<b>只调用</b> {@link OrderCompensationGateway#recordAudit}，
     * 不调用任何 CAS / 退款 / 库存 / 落库方法。
     */
    public CompensationResult reconcileUnsettledOrders() {
        List<OrderItem> candidates = unsettledCandidates(
            properties.reconcileMinAge(), properties.reconcileBatchLimit(), false);
        int diffs = 0;
        int skipped = 0;
        int failed = 0;
        int processed = 0;
        for (OrderItem order : limited(candidates, properties.reconcileBatchLimit())) {
            processed++;
            try {
                Optional<UpstreamOrderSnapshot> fetched = gateway.fetchUpstreamOrderStatus(order);
                if (fetched.isEmpty()) {
                    skipped++;
                    continue;
                }
                UpstreamOrderSnapshot upstream = fetched.get();
                OrderStatus resolved = upstream.resolvedLocalStatus(order.status());
                if (resolved == order.status()
                    && upstreamNoMatches(order.upstreamOrderNo(), upstream.upstreamOrderNo())) {
                    continue;
                }
                gateway.recordAudit("ORDER_RECONCILE_DIFF", "ORDER", order.orderNo(), diffRemark(order, upstream, resolved));
                diffs++;
            } catch (SupplierTransportException ex) {
                // 对账同样遵守 A4：查不到就是查不到，只跳过
                skipped++;
                log.info("compensation reconcile: upstream unreachable for order {}: {}",
                    order.orderNo(), ex.getMessage());
            } catch (SupplierBusinessException ex) {
                // 上游明确说没有这笔单，本身就是一条要人工看的差异（对账不动数据）
                gateway.recordAudit("ORDER_RECONCILE_DIFF", "ORDER", order.orderNo(),
                    "本地=" + order.status() + "，上游明确应答未受理：" + ex.getMessage() + "（仅报告，未自动修改）");
                diffs++;
            } catch (UnsupportedOperationException ex) {
                skipped++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("compensation reconcile order {} failed: {}", order.orderNo(), ex.toString());
            }
        }
        return new CompensationResult(candidates.size(), processed, diffs, skipped, failed);
    }

    private static String diffRemark(OrderItem order, UpstreamOrderSnapshot upstream, OrderStatus resolved) {
        StringBuilder remark = new StringBuilder("对账差异：本地=").append(order.status())
            .append("，上游=").append(resolved)
            .append("（上游状态标签=").append(defaultText(upstream.upstreamStatusLabel(), "-")).append("）");
        if (!upstreamNoMatches(order.upstreamOrderNo(), upstream.upstreamOrderNo())) {
            remark.append("；本地上游单号=").append(defaultText(order.upstreamOrderNo(), "-"))
                .append("，上游返回=").append(defaultText(upstream.upstreamOrderNo(), "-"));
        }
        remark.append("；仅报告，未自动修改订单数据");
        return remark.toString();
    }

    /** 本地上游单号为空时不算差异（超时场景本来就拿不到单号）。 */
    private static boolean upstreamNoMatches(String local, String remote) {
        if (local == null || local.isBlank() || remote == null || remote.isBlank()) {
            return true;
        }
        return Objects.equals(local, remote);
    }

    // ================================================================ 内部

    /** 中间态且停留超过 minAge 的直充订单；卡密类不走上游，天然排除。 */
    private List<OrderItem> unsettledCandidates(
        java.time.Duration minAge,
        int limit,
        boolean withoutCallback
    ) {
        OffsetDateTime deadline = OffsetDateTime.now().minus(minAge);
        return gateway.unsettledOrderCandidates(deadline, limit, withoutCallback);
    }

    private static <T> List<T> limited(List<T> source, int limit) {
        return source.size() <= limit ? source : source.subList(0, limit);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
