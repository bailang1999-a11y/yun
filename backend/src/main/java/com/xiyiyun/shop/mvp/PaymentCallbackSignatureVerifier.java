package com.xiyiyun.shop.mvp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PaymentCallbackSignatureVerifier {
    private final String callbackSecret;

    public PaymentCallbackSignatureVerifier(
        @Value("${xiyiyun.payment.callback-secret:xiyiyun_mock_payment_secret}") String callbackSecret
    ) {
        this.callbackSecret = callbackSecret;
    }

    public void verify(String provider, PaymentCallbackRequest request) {
        if (request == null || !StringUtils.hasText(request.signature())) {
            throw new IllegalArgumentException("missing payment callback signature");
        }

        String expected = hmacSha256(callbackSecret, payload(provider, request));
        if (!constantTimeEquals(expected, request.signature())) {
            throw new IllegalArgumentException("invalid payment callback signature");
        }
    }

    private String payload(String provider, PaymentCallbackRequest request) {
        return String.join(
            "\n",
            normalize(provider),
            normalize(request.paymentNo()),
            normalize(request.orderNo()),
            normalize(request.status()),
            normalize(request.channelTradeNo())
        );
    }

    private String hmacSha256(String secret, String payload) {
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
            throw new IllegalStateException("payment callback signature calculation failed", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            normalize(expected).getBytes(StandardCharsets.UTF_8),
            normalize(actual).getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
