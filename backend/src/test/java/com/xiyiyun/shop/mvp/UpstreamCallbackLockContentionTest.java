package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 批次8B 回归测试：上游回调期间的阻塞 HTTP <b>不得</b>持有仓储监视器。
 *
 * <h2>修复前的故障形态</h2>
 * 5 个 {@code handleXxxOrderCallback} 都是 {@code public synchronized}（锁 {@code this}），
 * 而方法体里会调 {@code upstreamGoodsSnapshot} 去真实请求上游商品接口。
 * 本类另有 ~40 个 {@code public synchronized} 方法共用同一把锁，于是：
 * <b>任意一家上游变慢 → 全站下单/登录/支付一起阻塞</b>。
 * 上游超时按供应商配置可到数秒，回调又是上游高频推送，
 * 这是把「一家上游抖动」放大成「整站不可用」的最短路径。
 *
 * <h2>怎么证明锁真的放开了</h2>
 * 用 {@link CountDownLatch} 把上游商品查询卡在 HTTP 那一跳（{@code httpEntered} 通知主线程
 * 「我已经进到 HTTP 里了」，{@code releaseHttp} 决定何时放行）。
 * 此时主线程去调一个 {@code public synchronized} 方法：
 * <ul>
 *   <li>修复前：主线程拿不到锁，卡到 HTTP 放行，用例超时失败；</li>
 *   <li>修复后：主线程立刻返回，回调线程仍卡在锁外的 HTTP 上。</li>
 * </ul>
 * 断言的是「另一个 synchronized 方法能在 HTTP 未返回时完成」这个<b>可观测行为</b>，
 * 而不是去反射检查锁状态 —— 后者换个实现就失效。
 */
class UpstreamCallbackLockContentionTest {
    private static final Long DIRECT_GOODS_ID = 10002L;
    private static final Long CHANNEL_ID = 30001L;
    private static final Long SUPPLIER_ID = 20001L;
    private static final String SUPPLIER_SECRET = "chengquan-callback-secret";
    private static final String SUPPLIER_APP_ID = "cq-app-id";

    /**
     * 核心用例：上游商品查询卡住时，另一个 synchronized 方法必须能照常完成。
     *
     * <p>10 秒超时是「死锁 / 锁等待」的兜底判定：修复前主线程会一直等到 HTTP 放行，
     * 而本用例永不主动放行，故修复前必然撞上这个超时。
     */
    @Test
    @Timeout(10)
    void slowUpstreamGoodsQueryMustNotBlockOtherRepositoryCalls() throws Exception {
        InMemoryShopRepository repository = newRepository();
        makeSupplierChengquan(repository);
        OrderItem procuring = procuringDirectOrder(repository);

        CountDownLatch httpEntered = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        repository.replaceSupplierHttpClientForTest(new BlockingHttpClientStub(httpEntered, releaseHttp));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> callback = pool.submit(
                () -> repository.handleChengquanOrderCallback(SUPPLIER_ID, signedCallbackBody(procuring.orderNo())));

            assertThat(httpEntered.await(5, TimeUnit.SECONDS))
                .as("回调线程应当已经进入上游商品查询的 HTTP 调用")
                .isTrue();

            // 此刻回调线程正卡在 HTTP 里。修复前它同时握着 this 锁，下面这行会一直等。
            long startedAt = System.nanoTime();
            List<AdminStaffItem> staff = repository.listAdminStaff();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(staff).as("被阻塞的回调不应影响其他方法的返回值").isNotNull();
            assertThat(elapsedMs)
                .as("上游 HTTP 未返回时，其他 synchronized 方法必须立即完成（说明锁没被 IO 占着）")
                .isLessThan(3_000L);

            releaseHttp.countDown();
            assertThat(callback.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        } finally {
            releaseHttp.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 把 IO 挪出锁之后，回调本身的业务结果必须一字不差：
     * 状态推进到 DELIVERED、上游单号落到订单上。
     *
     * <p>没有这条，上一个用例可以被「回调什么都不做」骗过去。
     */
    @Test
    @Timeout(20)
    void callbackMustStillApplyUpstreamResultAfterLockSplit() {
        InMemoryShopRepository repository = newRepository();
        makeSupplierChengquan(repository);
        OrderItem procuring = procuringDirectOrder(repository);
        repository.replaceSupplierHttpClientForTest(new FailingGoodsHttpClientStub());

        String result = repository.handleChengquanOrderCallback(SUPPLIER_ID, signedCallbackBody(procuring.orderNo()));

        assertThat(result).as("回调应答文案不能变，上游按字符串匹配").isEqualTo("OK");
        OrderItem applied = orders(repository).get(procuring.orderNo());
        assertThat(applied.status())
            .as("上游回调 success 必须推进为已发货")
            .isEqualTo(OrderStatus.DELIVERED);
        assertThat(applied.upstreamOrderNo())
            .as("上游单号是对账依据，回调必须落库")
            .isEqualTo("CQ-8B-0001");
        assertThat(applied.channelAttempts())
            .as("渠道尝试应被回调结果回填")
            .anySatisfy(attempt -> assertThat(attempt.callbackStatus()).isNotBlank());
    }

    /**
     * 上游「至少一次」投递：同一份回调重推第二次，必须照常返回成功应答。
     *
     * <p>修复前 {@link InMemoryShopRepository#callbackAttemptOf} 只认
     * {@code status ∈ {SUCCESS, PROCURING}}，而首次回调后 {@code enrichAttempt}
     * 已把该状态覆写成 {@code DELIVERED}，于是第二次回调抛
     * {@code chengquan callback order channel mismatch}。
     * 上游收到错误应答会持续重推并最终判定我方回调不可达。
     */
    @Test
    @Timeout(20)
    void duplicateCallbackMustBeIdempotentInsteadOfChannelMismatch() {
        InMemoryShopRepository repository = newRepository();
        makeSupplierChengquan(repository);
        OrderItem procuring = procuringDirectOrder(repository);
        repository.replaceSupplierHttpClientForTest(new FailingGoodsHttpClientStub());
        Map<String, Object> body = signedCallbackBody(procuring.orderNo());

        assertThat(repository.handleChengquanOrderCallback(SUPPLIER_ID, body)).isEqualTo("OK");
        OffsetDateTime firstDeliveredAt = orders(repository).get(procuring.orderNo()).deliveredAt();
        assertThat(firstDeliveredAt).as("首次回调应记录发货时间").isNotNull();

        assertThat(repository.handleChengquanOrderCallback(SUPPLIER_ID, body))
            .as("上游重推同一份回调必须幂等成功，不能报渠道不匹配")
            .isEqualTo("OK");

        OrderItem applied = orders(repository).get(procuring.orderNo());
        assertThat(applied.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(applied.deliveredAt())
            .as("重复回调不得刷新发货时间，否则发货时长统计被抹掉")
            .isEqualTo(firstDeliveredAt);
        assertThat(applied.channelAttempts()).hasSize(1);
    }

    /**
     * 真正的渠道不匹配仍必须报错：放宽重复回调判定不能顺带把「串单」也吞掉。
     */
    @Test
    @Timeout(20)
    void callbackForAnotherSupplierMustStillFail() {
        InMemoryShopRepository repository = newRepository();
        makeSupplierChengquan(repository);
        OrderItem created = repository.createOrder(
            new CreateOrderRequest(DIRECT_GOODS_ID, 1, "13800000001", "8b mismatch", "8b-mismatch-" + System.nanoTime(), "h5"),
            90001L, "127.0.0.1", "h5"
        );
        Map<String, OrderItem> orders = orders(repository);
        // 渠道尝试属于另一家供应商，且从未提交成功 —— 橙券的回调不该被认领。
        ChannelAttemptItem foreign = new ChannelAttemptItem(
            CHANNEL_ID, 999_999L, "别家供应商", "OTHER-GOODS", 1, "FAILED", "提交失败", OffsetDateTime.now()
        );
        OrderItem tampered = orders.get(created.orderNo()).withProcurementResult(
            OrderStatus.PROCURING, List.of(), List.of(foreign), "", OffsetDateTime.now(), null
        );
        orders.put(tampered.orderNo(), tampered);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> repository.handleChengquanOrderCallback(SUPPLIER_ID, signedCallbackBody(tampered.orderNo())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("chengquan callback order channel mismatch");
    }

    /**
     * 并发回调：同一笔订单被上游重复推送时，锁内重读必须让最终状态收敛，
     * 且不会因为 HTTP 期间订单被改动而写回过期快照。
     */
    @Test
    @Timeout(30)
    void concurrentCallbacksOnSameOrderMustConvergeToDelivered() throws Exception {
        InMemoryShopRepository repository = newRepository();
        makeSupplierChengquan(repository);
        OrderItem procuring = procuringDirectOrder(repository);
        repository.replaceSupplierHttpClientForTest(new FailingGoodsHttpClientStub());

        int threads = 8;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return repository.handleChengquanOrderCallback(
                        SUPPLIER_ID, signedCallbackBody(procuring.orderNo()));
                }));
            }
            for (Future<String> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("OK");
            }
        } finally {
            pool.shutdownNow();
        }

        OrderItem applied = orders(repository).get(procuring.orderNo());
        assertThat(applied.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(applied.upstreamOrderNo()).isEqualTo("CQ-8B-0001");
        assertThat(applied.channelAttempts())
            .as("重复回调不得把渠道尝试记录越写越多")
            .hasSize(1);
    }

    /** 卡在上游商品查询的 HTTP 桩：先通知「已进入」，再等主线程放行。 */
    private static final class BlockingHttpClientStub extends SupplierHttpClient {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingHttpClientStub(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            block();
            throw transportFailure(profile, request);
        }

        @Override
        public String post(SupplierHttpProfile profile, SupplierHttpRequest request) {
            block();
            throw transportFailure(profile, request);
        }

        private void block() {
            entered.countDown();
            try {
                // 上限只是兜底，正常路径由主线程 countDown 放行。
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 上游商品查询直接失败的桩。
     *
     * <p>商品快照取不到只影响渠道尝试上的商品名/成本价这类展示字段，
     * {@code upstreamGoodsSnapshot} 内部已 catch 成 empty，
     * 订单状态推进不依赖它 —— 这正是它可以被移到锁外的前提。
     */
    private static final class FailingGoodsHttpClientStub extends SupplierHttpClient {
        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw transportFailure(profile, request);
        }

        @Override
        public String post(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw transportFailure(profile, request);
        }
    }

    private static SupplierTransportException transportFailure(
        SupplierHttpProfile profile,
        SupplierHttpRequest request
    ) {
        return new SupplierTransportException(
            profile.supplierCode(),
            request.action(),
            profile.supplierCode() + " " + request.action() + " failed: request timed out",
            null,
            new java.net.http.HttpTimeoutException("request timed out")
        );
    }

    /** 构造一份签名合法的橙券回调报文（status=success → DELIVERED）。 */
    private static Map<String, Object> signedCallbackBody(String orderNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", SUPPLIER_APP_ID);
        body.put("order_no", orderNo);
        body.put("cq_order_no", "CQ-8B-0001");
        body.put("status", "success");
        body.put("message", "充值成功");
        body.put("amount", "20.00");
        body.put("sign", ChengquanSignatureUtil.sign(body, SUPPLIER_SECRET));
        return body;
    }

    /** 把种子供应商改成鼎信橙券，并配好回调验签所需的 app_id / 密钥。 */
    private static void makeSupplierChengquan(InMemoryShopRepository repository) {
        Map<Long, SupplierItem> suppliers = field(repository, "suppliers");
        SupplierItem current = suppliers.get(SUPPLIER_ID);
        suppliers.put(SUPPLIER_ID, new SupplierItem(
            current.id(),
            current.name(),
            "CHENGQUAN",
            "https://chengquan.test.invalid",
            "cq-app-key",
            current.appSecretMasked(),
            "cq-user",
            SUPPLIER_APP_ID,
            SUPPLIER_SECRET,
            current.apiKeyMasked(),
            current.callbackUrl(),
            5,
            BigDecimal.valueOf(100000),
            "ENABLED",
            current.remark(),
            current.lastSyncAt()
        ));
    }

    /**
     * 造一笔处于 PROCURING、且带有该供应商成功渠道尝试的直充订单 —— 回调的合法入口状态。
     * {@code supplierGoodsId} 必须非空，否则 {@code upstreamGoodsSnapshot} 会直接短路返回，
     * 测不到 HTTP 那一跳。
     */
    private static OrderItem procuringDirectOrder(InMemoryShopRepository repository) {
        CreateOrderRequest request = new CreateOrderRequest(
            DIRECT_GOODS_ID, 1, "13800000001", "8b lock case", "8b-lock-" + System.nanoTime(), "h5"
        );
        OrderItem created = repository.createOrder(request, 90001L, "127.0.0.1", "h5");
        Map<String, OrderItem> orders = orders(repository);
        ChannelAttemptItem attempt = new ChannelAttemptItem(
            CHANNEL_ID, SUPPLIER_ID, "鼎信橙券", "CQ-GOODS-1", 1, "SUCCESS", "已提交上游", OffsetDateTime.now()
        );
        OrderItem procuring = orders.get(created.orderNo()).withProcurementResult(
            OrderStatus.PROCURING,
            List.of(),
            List.of(attempt),
            "已提交上游，等待回调",
            OffsetDateTime.now(),
            null
        );
        orders.put(procuring.orderNo(), procuring);
        return procuring;
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
    }

    private static Map<String, OrderItem> orders(InMemoryShopRepository repository) {
        return field(repository, "orders");
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> field(InMemoryShopRepository repository, String name) {
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
