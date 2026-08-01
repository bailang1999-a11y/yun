package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 批次5 / B1：支付回调签名加固。
 *
 * <p>覆盖四条验收：金额被篡改必拒、timestamp 过期必拒、nonce 重放必拒、
 * 回调金额与订单应付金额不一致必拒。
 */
class PaymentCallbackSignatureHardeningTest {
    private static final String SECRET = "unit-test-callback-secret";
    private static final BigDecimal AMOUNT = new BigDecimal("12.3400");

    private PaymentCallbackSignatureVerifier strictVerifier() {
        return new PaymentCallbackSignatureVerifier(SECRET, true, 300L, (RedisSecurityStateStore) null);
    }

    @Test
    void tamperedAmountBreaksSignature() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        PaymentCallbackRequest signed = signedV2("mock", "PAY-1", "ORDER-1", "SUCCESS", "TRADE-1", AMOUNT, nowMillis(), "nonce-amount");
        // 攻击者拿着合法签名把金额改成 0.01
        PaymentCallbackRequest tampered = new PaymentCallbackRequest(
            signed.paymentNo(), signed.orderNo(), signed.status(), signed.channelTradeNo(),
            new BigDecimal("0.0100"), signed.timestamp(), signed.nonce(), signed.signature()
        );

        assertThatThrownBy(() -> verifier.verify("mock", tampered))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid payment callback signature");
    }

    @Test
    void expiredTimestampIsRejected() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        String staleTimestamp = String.valueOf(System.currentTimeMillis() - 600_000L);
        PaymentCallbackRequest stale = signedV2("mock", "PAY-2", "ORDER-2", "SUCCESS", "TRADE-2", AMOUNT, staleTimestamp, "nonce-stale");

        assertThatThrownBy(() -> verifier.verify("mock", stale))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("payment callback timestamp expired");
    }

    @Test
    void malformedTimestampIsRejected() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        PaymentCallbackRequest bad = signedV2("mock", "PAY-2B", "ORDER-2B", "SUCCESS", "TRADE-2B", AMOUNT, "not-a-timestamp", "nonce-bad-ts");

        assertThatThrownBy(() -> verifier.verify("mock", bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid payment callback timestamp");
    }

    @Test
    void replayedNonceIsRejectedOnSecondUse() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        PaymentCallbackRequest request = signedV2("mock", "PAY-3", "ORDER-3", "SUCCESS", "TRADE-3", AMOUNT, nowMillis(), "nonce-replay");

        assertThatCode(() -> verifier.verify("mock", request)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify("mock", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("payment callback nonce replayed");
    }

    @Test
    void strictModeRejectsLegacyCallbackWithoutNewFields() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        String payload = String.join("\n", "mock", "PAY-4", "ORDER-4", "SUCCESS", "TRADE-4");
        PaymentCallbackRequest legacy = new PaymentCallbackRequest(
            "PAY-4", "ORDER-4", "SUCCESS", "TRADE-4", hmac(payload));

        assertThatThrownBy(() -> verifier.verify("mock", legacy))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("missing payment callback signature fields");
    }

    @Test
    void transitionModeStillAcceptsLegacyCallbackButRejectsHalfFilledOne() {
        PaymentCallbackSignatureVerifier verifier = new PaymentCallbackSignatureVerifier(SECRET);
        String payload = String.join("\n", "mock", "PAY-5", "ORDER-5", "SUCCESS", "TRADE-5");
        PaymentCallbackRequest legacy = new PaymentCallbackRequest(
            "PAY-5", "ORDER-5", "SUCCESS", "TRADE-5", hmac(payload));

        assertThatCode(() -> verifier.verify("mock", legacy)).doesNotThrowAnyException();

        // 只带 amount 却不带 timestamp/nonce → 仍然拒绝，防止绕过重放防护
        PaymentCallbackRequest halfFilled = new PaymentCallbackRequest(
            "PAY-5", "ORDER-5", "SUCCESS", "TRADE-5", AMOUNT, null, null, hmac(payload));
        assertThatThrownBy(() -> verifier.verify("mock", halfFilled))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("missing payment callback signature fields");
    }

    @Test
    void validV2CallbackPasses() {
        PaymentCallbackSignatureVerifier verifier = strictVerifier();
        PaymentCallbackRequest request = signedV2("mock", "PAY-6", "ORDER-6", "SUCCESS", "TRADE-6", AMOUNT, nowMillis(), "nonce-ok");

        assertThatCode(() -> verifier.verify("mock", request)).doesNotThrowAnyException();
    }

    @Test
    void callbackAmountNotMatchingOrderIsRejectedAndLogged() {
        InMemoryShopRepository repository = newRepository();
        OrderItem order = repository.createOrder(new CreateOrderRequest(10001L, 1, "", "amount check", "amount-mismatch-1"));
        PaymentItem payment = putPendingPayment(repository, "PAY-AMOUNT", order);
        BigDecimal wrongAmount = payment.amount().subtract(new BigDecimal("1.0000"));

        // 用错误金额重新签名：签名本身合法，只有金额与订单应付不一致
        PaymentCallbackRequest request = signedV2(
            "mock", payment.paymentNo(), order.orderNo(), "SUCCESS", "TRADE-AMOUNT", wrongAmount, nowMillis(), "nonce-order-amount");

        assertThatThrownBy(() -> repository.handlePaymentCallback("mock", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("payment callback amount mismatch");

        assertThat(repository.findOrder(order.orderNo())).get()
            .extracting(OrderItem::status).isEqualTo(OrderStatus.UNPAID);
        assertThat(repository.listPaymentCallbackLogs()).anySatisfy(entry -> {
            assertThat(entry.paymentNo()).isEqualTo(payment.paymentNo());
            assertThat(entry.result()).isEqualTo("FAILED");
            assertThat(entry.message()).isEqualTo("payment callback amount mismatch");
        });
    }

    @Test
    void callbackAmountMatchingOrderIsAccepted() {
        InMemoryShopRepository repository = newRepository();
        OrderItem order = repository.createOrder(new CreateOrderRequest(10001L, 1, "", "amount ok", "amount-match-1"));
        PaymentItem payment = putPendingPayment(repository, "PAY-AMOUNT-OK", order);

        PaymentCallbackRequest request = signedV2(
            "mock", payment.paymentNo(), order.orderNo(), "SUCCESS", "TRADE-AMOUNT-OK",
            payment.amount(), nowMillis(), "nonce-order-amount-ok");

        assertThatCode(() -> repository.handlePaymentCallback("mock", request)).doesNotThrowAnyException();
        assertThat(repository.findOrder(order.orderNo())).get()
            .extracting(OrderItem::status).isNotEqualTo(OrderStatus.UNPAID);
    }

    private static String nowMillis() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static PaymentCallbackRequest signedV2(
        String provider, String paymentNo, String orderNo, String status,
        String channelTradeNo, BigDecimal amount, String timestamp, String nonce
    ) {
        String payload = String.join(
            "\n", provider, paymentNo, orderNo, status, channelTradeNo,
            PaymentCallbackSignatureVerifier.canonicalAmount(amount), timestamp, nonce);
        return new PaymentCallbackRequest(paymentNo, orderNo, status, channelTradeNo, amount, timestamp, nonce, hmac(payload));
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
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

    private static String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
