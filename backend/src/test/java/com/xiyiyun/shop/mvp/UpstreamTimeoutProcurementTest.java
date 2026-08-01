package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 批次3 缺陷 A4 回归测试：上游下单超时 / 连接异常，订单必须转中间态 PROCURING，
 * 只有上游<b>明确拒单</b>才允许 FAILED。
 *
 * <p>修复前的行为：{@code procureWithFallback} 用 {@code catch (RuntimeException)} 把超时
 * 和明确拒单一并折叠为 FAILED，上游可能已受理并扣走我方预付款，我方却按失败处理
 * （甚至给用户退款），形成双向资损。</p>
 */
class UpstreamTimeoutProcurementTest {
    private static final Long DIRECT_GOODS_ID = 10002L;
    private static final Long CHANNEL_ID = 30001L;
    private static final Long SUPPLIER_ID = 20001L;

    @Test
    void upstreamTimeoutMustMoveOrderToProcuringInsteadOfFailed() {
        InMemoryShopRepository repository = newRepository();
        makeChannelSupplierApiBacked(repository);
        repository.replaceSupplierHttpClientForTest(new TimeoutHttpClientStub());
        OrderItem paid = paidDirectOrder(repository);

        OrderItem result = repository.retryProcurement(paid.orderNo());

        assertThat(result.status())
            .as("上游超时属于结果未知，必须转 PROCURING 等待对账，不能判 FAILED")
            .isEqualTo(OrderStatus.PROCURING);
        assertThat(result.deliveryMessage()).contains("结果未知");
        assertThat(result.channelAttempts())
            .as("渠道尝试记录也必须是 PROCURING，避免运营看到 FAILED 后手工退款")
            .anySatisfy(attempt -> assertThat(attempt.status()).isEqualTo("PROCURING"));
    }

    @Test
    void upstreamTimeoutOnSpecificChannelRetryMustAlsoStayProcuring() {
        InMemoryShopRepository repository = newRepository();
        makeChannelSupplierApiBacked(repository);
        repository.replaceSupplierHttpClientForTest(new TimeoutHttpClientStub());
        OrderItem paid = paidDirectOrder(repository);

        OrderItem result = repository.retryProcurementWithChannel(paid.orderNo(), CHANNEL_ID);

        assertThat(result.status()).isEqualTo(OrderStatus.PROCURING);
        assertThat(result.deliveryMessage()).contains("结果未知");
    }

    @Test
    void explicitUpstreamRejectionStillFailsTheOrder() {
        InMemoryShopRepository repository = newRepository();
        makeChannelSupplierApiBacked(repository);
        repository.replaceSupplierHttpClientForTest(new RejectingHttpClientStub());
        OrderItem paid = paidDirectOrder(repository);

        OrderItem result = repository.retryProcurement(paid.orderNo());

        assertThat(result.status())
            .as("上游明确拒单不存在资金未知，仍应判 FAILED")
            .isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void orderItemCarriesUpstreamOrderNoForReconciliation() {
        OrderItem order = new OrderItem(
            "ORDER-A4", 90001L, "", DIRECT_GOODS_ID, "直充商品", com.xiyiyun.shop.GoodsType.DIRECT,
            "h5", "", "", 1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.PROCURING, "13800000001",
            Map.of(), "", "", "", "", java.util.List.of(), java.util.List.of(), "", OffsetDateTime.now(), null, null
        );

        assertThat(order.upstreamOrderNo()).isEmpty();
        assertThat(order.withUpstreamOrderNo("UP-123").upstreamOrderNo()).isEqualTo("UP-123");
        assertThat(order.withUpstreamOrderNo("UP-123")
            .withProcurementResult(OrderStatus.DELIVERED, java.util.List.of(), java.util.List.of(), "ok", null, null)
            .upstreamOrderNo())
            .as("状态流转不能丢掉上游订单号，否则对账无从下手")
            .isEqualTo("UP-123");
    }

    /** 上游连接超时 / 读超时：SupplierHttpClient 统一抛 SupplierTransportException。 */
    private static final class TimeoutHttpClientStub extends SupplierHttpClient {
        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw new SupplierTransportException(
                profile.supplierCode(),
                request.action(),
                profile.supplierCode() + " " + request.action() + " failed: request timed out",
                null,
                new java.net.http.HttpTimeoutException("request timed out")
            );
        }

        @Override
        public String post(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw new SupplierTransportException(
                profile.supplierCode(),
                request.action(),
                profile.supplierCode() + " " + request.action() + " failed: request timed out",
                null,
                new java.net.http.HttpTimeoutException("request timed out")
            );
        }
    }

    /** 上游明确拒单：HTTP 200 且响应体带明确错误码。 */
    private static final class RejectingHttpClientStub extends SupplierHttpClient {
        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw new SupplierBusinessException(
                profile.supplierCode(), request.action(), "4001",
                profile.supplierCode() + " " + request.action() + " failed: code=4001 message=商品已下架"
            );
        }

        @Override
        public String post(SupplierHttpProfile profile, SupplierHttpRequest request) {
            throw new SupplierBusinessException(
                profile.supplierCode(), request.action(), "4001",
                profile.supplierCode() + " " + request.action() + " failed: code=4001 message=商品已下架"
            );
        }
    }

    /**
     * 把种子渠道指向的供应商改成真实 API 供应商（卡速售），使采购走适配器的 HTTP 路径。
     * 同时把另一条备用渠道停用，保证测试只观察单一渠道的失败分类。
     */
    private static void makeChannelSupplierApiBacked(InMemoryShopRepository repository) {
        Map<Long, SupplierItem> suppliers = field(repository, "suppliers");
        SupplierItem current = suppliers.get(SUPPLIER_ID);
        suppliers.put(SUPPLIER_ID, new SupplierItem(
            current.id(),
            current.name(),
            "KASUSHOU",
            "https://kasushou.test.invalid",
            "test-app-key",
            current.appSecretMasked(),
            "test-user",
            "test-app-id",
            "test-api-key",
            current.apiKeyMasked(),
            current.callbackUrl(),
            5,
            BigDecimal.valueOf(100000),
            "ENABLED",
            current.remark(),
            current.lastSyncAt()
        ));

        Map<Long, GoodsChannelItem> channels = catalogField(repository, "goodsChannels");
        channels.replaceAll((id, channel) -> {
            if (!DIRECT_GOODS_ID.equals(channel.goodsId()) || CHANNEL_ID.equals(id)) {
                return channel;
            }
            return new GoodsChannelItem(
                channel.id(), channel.goodsId(), channel.supplierId(), channel.supplierName(),
                channel.supplierGoodsId(), channel.priority(), channel.timeoutSeconds(), "DISABLED",
                channel.createdAt()
            );
        });
    }

    /** 造一笔已支付、处于 FAILED 的直充订单（retryProcurement 的合法入口状态）。 */
    private static OrderItem paidDirectOrder(InMemoryShopRepository repository) {
        CreateOrderRequest request = new CreateOrderRequest(
            DIRECT_GOODS_ID, 1, "13800000001", "a4 timeout case", "a4-timeout-" + System.nanoTime(), "h5"
        );
        OrderItem created = repository.createOrder(request, 90001L, "127.0.0.1", "h5");
        Map<String, OrderItem> orders = field(repository, "orders");
        OrderItem failed = orders.get(created.orderNo()).withProcurementResult(
            OrderStatus.FAILED,
            java.util.List.of(),
            java.util.List.of(),
            "上游返回失败",
            OffsetDateTime.now(),
            null
        );
        orders.put(failed.orderNo(), failed);
        return failed;
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
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

    /**
     * 批次7B / 任务B：goodsChannels 已随商品域搬到 {@link CatalogService}。
     * 这里只机械改反射目标（仓储字段 → catalogService 字段），断言逐字未变。
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> catalogField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("catalogService");
            holder.setAccessible(true);
            Object catalog = holder.get(repository);
            Field field = catalog.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(catalog);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

}
