package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemberOrderCallbackPayloadTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void separatesPublicAndCompleteSensitiveOrderFields() throws Exception {
        MemberOrderCallbackPayload.Event event = MemberOrderCallbackPayload.from(
            order("api", OrderStatus.DELIVERED), Instant.ofEpochSecond(1785427364L)
        ).orElseThrow();

        JsonNode payload = OBJECT_MAPPER.readTree(event.payloadJson());
        assertThat(event.eventType()).isEqualTo("order.succeeded");
        assertThat(payload.path("eventId").asText()).startsWith("evt_");
        assertThat(payload.path("timestamp").asLong()).isEqualTo(1785427364L);
        assertThat(payload.path("data").path("orderNo").asText()).isEqualTo("external-1");
        assertThat(payload.path("data").path("outTradeNo").asText()).isEqualTo("ORDER-1");
        assertThat(payload.path("data").path("orderStatus").asText()).isEqualTo("SUCCESS");
        assertThat(event.payloadJson()).doesNotContain("13800138000", "CARD-SECRET");
        assertThat(event.sensitiveJson()).contains(
            "13800138000", "CARD-SECRET", "PAY-1", "RAW-UPSTREAM-RESPONSE"
        );
    }

    @Test
    void ignoresStorefrontAndNonTerminalOrders() {
        assertThat(MemberOrderCallbackPayload.from(
            order("web", OrderStatus.DELIVERED), Instant.now()
        )).isEmpty();
        assertThat(MemberOrderCallbackPayload.from(
            order("api", OrderStatus.PROCURING), Instant.now()
        )).isEmpty();
    }

    @Test
    void keepsTheRawUpstreamFailureOnlyInTheEncryptedSnapshot() {
        OrderItem failed = order("api", OrderStatus.FAILED).withStatus(
            OrderStatus.FAILED,
            "upstream rejected account 13800138000 with card CARD-SECRET",
            OffsetDateTime.parse("2026-07-31T00:00:00+08:00")
        );

        MemberOrderCallbackPayload.Event event = MemberOrderCallbackPayload.from(
            failed, Instant.now()
        ).orElseThrow();

        assertThat(event.payloadJson()).contains("order processing failed")
            .doesNotContain("13800138000", "CARD-SECRET", "upstream rejected");
        assertThat(event.sensitiveJson()).contains("13800138000", "CARD-SECRET", "upstream rejected");
    }

    @Test
    void keepsOneEventIdForTheSameOccurrenceButDistinguishesARetryFailure() {
        OrderItem firstFailure = order("api", OrderStatus.FAILED);
        String first = MemberOrderCallbackPayload.from(
            firstFailure, Instant.ofEpochSecond(1)
        ).orElseThrow().eventId();
        String duplicate = MemberOrderCallbackPayload.from(
            firstFailure, Instant.ofEpochSecond(2)
        ).orElseThrow().eventId();
        OrderItem secondFailure = firstFailure.withStatus(
            OrderStatus.FAILED,
            "second failure",
            firstFailure.deliveredAt().plusSeconds(1)
        );
        String retried = MemberOrderCallbackPayload.from(
            secondFailure, Instant.ofEpochSecond(3)
        ).orElseThrow().eventId();

        assertThat(duplicate).isEqualTo(first);
        assertThat(retried).isNotEqualTo(first);
    }

    private OrderItem order(String platform, OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-31T00:00:00+08:00");
        return new OrderItem(
            "ORDER-1", 90001L, "buyer", 10004L, "测试商品", GoodsType.DIRECT,
            platform, "", "", 1, BigDecimal.ONE, BigDecimal.ONE, status,
            "13800138000", Map.of("account", "13800138000"), "", "external-1",
            "PAY-1", "balance", List.of("CARD-SECRET"), List.of(new ChannelAttemptItem(
                11L, 22L, "supplier", "supplier-goods", "supplier product", BigDecimal.ONE,
                "SUCCESS", "DELIVERED", "callback ok", "RAW-UPSTREAM-RESPONSE",
                "https://callback.example.com/order",
                1, "SUCCESS", "completed", now
            )), "", now, now, now
        );
    }
}
