package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.xiyiyun.shop.ApiResponse;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaymentCallbackTest {
    private static final String CALLBACK_SECRET = "unit-test-callback-secret";

    @Test
    void invalidSignatureDoesNotInvokeRepositoryProcessing() {
        InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
        PaymentMvpController controller = new PaymentMvpController(
            repository,
            new PaymentCallbackSignatureVerifier(CALLBACK_SECRET)
        );

        ApiResponse<OrderItem> response = controller.callback("mock", new PaymentCallbackRequest(
            "PAY-INVALID",
            "ORDER-INVALID",
            "SUCCESS",
            "TRADE-INVALID",
            "bad-signature"
        ));

        assertThat(response.code()).isEqualTo(-1);
        assertThat(response.message()).isEqualTo("invalid payment callback signature");
        verify(repository).recordPaymentCallback("mock", new PaymentCallbackRequest(
            "PAY-INVALID",
            "ORDER-INVALID",
            "SUCCESS",
            "TRADE-INVALID",
            "bad-signature"
        ), "FAILED", "invalid payment callback signature");
        verify(repository, never()).handlePaymentCallback(anyString(), any());
    }

    @Test
    void mismatchedCallbackOrderNumberFailsAndLeavesOrderUnchanged() {
        InMemoryShopRepository repository = newRepository();
        OrderItem order = repository.createOrder(new CreateOrderRequest(10001L, 1, "", "callback mismatch", "mismatch-1"));
        PaymentItem payment = putPendingPayment(repository, "PAY-MISMATCH", order);

        assertThatThrownBy(() -> repository.handlePaymentCallback("mock", signedRequest(
            "mock",
            payment.paymentNo(),
            "OTHER-ORDER",
            "SUCCESS",
            "TRADE-MISMATCH"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("payment callback order mismatch");

        assertThat(repository.findOrder(order.orderNo())).get().extracting(OrderItem::status).isEqualTo(OrderStatus.UNPAID);
        assertThat(repository.listPaymentCallbackLogs()).anySatisfy(log -> {
            assertThat(log.paymentNo()).isEqualTo(payment.paymentNo());
            assertThat(log.result()).isEqualTo("FAILED");
            assertThat(log.message()).isEqualTo("payment callback order mismatch");
        });
    }

    @Test
    void cancelledOrderCannotBeAdvancedBySuccessfulCallback() {
        InMemoryShopRepository repository = newRepository();
        OrderItem order = repository.createOrder(new CreateOrderRequest(10001L, 1, "", "cancel before callback", "cancel-1"));
        PaymentItem payment = putPendingPayment(repository, "PAY-CANCELLED", order);
        repository.cancelOrder(order.orderNo(), order.userId());

        assertThatThrownBy(() -> repository.handlePaymentCallback("mock", signedRequest(
            "mock",
            payment.paymentNo(),
            order.orderNo(),
            "SUCCESS",
            "TRADE-CANCELLED"
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("order cannot accept payment callback in current status");

        assertThat(repository.findOrder(order.orderNo())).get().extracting(OrderItem::status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(repository.listPaymentCallbackLogs()).anySatisfy(log -> {
            assertThat(log.paymentNo()).isEqualTo(payment.paymentNo());
            assertThat(log.result()).isEqualTo("FAILED");
            assertThat(log.message()).isEqualTo("order cannot accept payment callback in current status");
        });
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(mock(OrderRealtimeBroadcaster.class), "admin", "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq", "Admin");
    }

    private static PaymentItem putPendingPayment(InMemoryShopRepository repository, String paymentNo, OrderItem order) {
        PaymentItem payment = new PaymentItem(
            paymentNo,
            order.orderNo(),
            order.userId(),
            "wechat",
            order.payAmount() == null ? BigDecimal.ZERO : order.payAmount(),
            "PENDING",
            "",
            OffsetDateTime.now(),
            null
        );
        payments(repository).put(paymentNo, payment);
        return payment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PaymentItem> payments(InMemoryShopRepository repository) {
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField("payments");
            field.setAccessible(true);
            return (Map<String, PaymentItem>) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static PaymentCallbackRequest signedRequest(
        String provider,
        String paymentNo,
        String orderNo,
        String status,
        String channelTradeNo
    ) {
        String payload = String.join("\n", provider, paymentNo, orderNo, status, channelTradeNo);
        return new PaymentCallbackRequest(paymentNo, orderNo, status, channelTradeNo, hmacSha256(CALLBACK_SECRET, payload));
    }

    private static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
