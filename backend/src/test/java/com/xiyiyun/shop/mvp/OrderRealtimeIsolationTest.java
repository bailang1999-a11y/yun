package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderRealtimeIsolationTest {
    @Test
    void publishFailureDoesNotEscapeTheOrderPersistencePath() {
        OrderEventPublisher publisher = mock(OrderEventPublisher.class);
        when(publisher.toString()).thenReturn("publisher");
        org.mockito.Mockito.doThrow(new IllegalStateException("TEXT_PARTIAL_WRITING"))
            .when(publisher).publish(org.mockito.ArgumentMatchers.any());
        InMemoryShopRepository repository = new InMemoryShopRepository(
            publisher, "admin", "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq", "Admin"
        );
        OffsetDateTime now = OffsetDateTime.now();
        OrderItem order = new OrderItem(
            "ORDER-REALTIME-ISOLATION", 90001L, "buyer", 10003L, "manual", GoodsType.MANUAL,
            "h5", "", "", 1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.WAITING_MANUAL,
            "", Map.of(), "", "request", "", "", List.of(), List.of(), "waiting", now, now, null, ""
        );

        assertThatCode(() -> repository.orderCompensationGateway().saveAndPublishOrder(order))
            .doesNotThrowAnyException();
    }
}
