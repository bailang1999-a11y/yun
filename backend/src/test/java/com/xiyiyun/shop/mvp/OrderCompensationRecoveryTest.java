package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderCompensationRecoveryTest {
    @Test
    void recoversOnlyPaidDirectOrdersThatHaveNoSubmissionEvidence() {
        OrderCompensationGateway gateway = mock(OrderCompensationGateway.class);
        OrderItem emptySubmission = procuringOrder("EMPTY", "", List.of());
        OrderItem submitted = procuringOrder("SUBMITTED", "UP-1", List.of());
        OrderItem historical = withMessage(
            procuringOrder("HISTORICAL", "", List.of()), "支付成功，直充订单进入采购流程");
        when(gateway.fundsLedgerEnabled()).thenReturn(true);
        when(gateway.unsettledOrderCandidates(any(), anyInt(), anyBoolean()))
            .thenReturn(List.of(emptySubmission, submitted, historical));
        when(gateway.recoverUnsubmittedProcurement(emptySubmission)).thenReturn(Optional.of(emptySubmission));

        OrderCompensationService.CompensationResult result = new OrderCompensationService(
            gateway, OrderCompensationProperties.defaults().withAges(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)
            )
        ).pollUnsettledOrders();

        assertThat(result.changed()).isEqualTo(1);
        verify(gateway).recoverUnsubmittedProcurement(emptySubmission);
        verify(gateway, never()).recoverUnsubmittedProcurement(submitted);
        verify(gateway, never()).recoverUnsubmittedProcurement(historical);
        verify(gateway).fetchUpstreamOrderStatus(submitted);
        verify(gateway).fetchUpstreamOrderStatus(historical);
    }

    private static OrderItem procuringOrder(String orderNo, String upstreamOrderNo, List<ChannelAttemptItem> attempts) {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(2);
        return new OrderItem(
            orderNo, 90001L, "buyer", 10013L, "direct", GoodsType.DIRECT, "api", "", "", 1,
            BigDecimal.ONE, BigDecimal.ONE, OrderStatus.PROCURING, "13800000001", Map.of(), "", "request",
            "", "", List.of(), attempts, OrderCompensationService.UNSUBMITTED_PROCUREMENT_MESSAGE,
            now, now, null, upstreamOrderNo, null
        );
    }

    private static OrderItem withMessage(OrderItem order, String message) {
        return order.withStatus(order.status(), message, order.deliveredAt());
    }
}
