package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderRequestIdRefreshTest {
    private static final Long SUPPLIER_ID = 20001L;
    private static final String REQUEST_ID = "agiso-request-id-refresh";

    @Test
    void requestIdLookupRefreshesUpstreamWithoutBreakingUserScopeOrIdempotency() {
        InMemoryShopRepository repository = newRepository();
        configureSupplier(repository);
        DeliveredOrderHttpStub http = new DeliveredOrderHttpStub();
        repository.replaceSupplierHttpClientForTest(http);
        CreateOrderRequest request = new CreateOrderRequest(
            10002L, 1, "13800000001", "Agiso sync order", REQUEST_ID, "api"
        );
        OrderItem created = repository.createOrder(request, 90001L, "127.0.0.1", "api");
        putProcuringOrder(repository, created);

        assertThat(repository.findOrderByRequestId(90002L, REQUEST_ID)).isEmpty();
        assertThat(http.calls()).isZero();

        OrderItem refreshed = repository.findOrderByRequestId(90001L, REQUEST_ID).orElseThrow();
        OrderItem repeated = repository.findOrderByRequestId(90001L, REQUEST_ID).orElseThrow();
        OrderItem idempotent = repository.createOrder(request, 90001L, "127.0.0.1", "api");

        assertThat(refreshed.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(repeated.orderNo()).isEqualTo(created.orderNo());
        assertThat(idempotent.orderNo()).isEqualTo(created.orderNo());
        assertThat(http.calls()).isEqualTo(1);
        assertThat(orders(repository).values())
            .filteredOn(order -> REQUEST_ID.equals(order.requestId()))
            .hasSize(1);
    }

    private static void putProcuringOrder(InMemoryShopRepository repository, OrderItem created) {
        ChannelAttemptItem attempt = new ChannelAttemptItem(
            30001L, SUPPLIER_ID, "卡速售", "", 1, "SUCCESS", "已提交上游", OffsetDateTime.now()
        );
        OrderItem procuring = created.withProcurementResult(
            OrderStatus.PROCURING,
            List.of(),
            List.of(attempt),
            "已提交上游，等待结果",
            OffsetDateTime.now(),
            null
        );
        orders(repository).put(procuring.orderNo(), procuring);
    }

    private static void configureSupplier(InMemoryShopRepository repository) {
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
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
    }

    private static final class DeliveredOrderHttpStub extends SupplierHttpClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            calls.incrementAndGet();
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("ordersn", "UPSTREAM-REFRESHED");
            data.put("status", 3);
            data.put("recharge_hints", "充值成功");
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("code", 200);
            root.set("data", data);
            return root;
        }

        int calls() {
            return calls.get();
        }
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
