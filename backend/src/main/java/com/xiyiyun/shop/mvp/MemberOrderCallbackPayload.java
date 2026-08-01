package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MemberOrderCallbackPayload {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MemberOrderCallbackPayload() {
    }

    public static Optional<Event> from(OrderItem order, Instant occurredAt) {
        if (order == null || order.userId() == null || !"api".equalsIgnoreCase(clean(order.platform()))) {
            return Optional.empty();
        }
        String eventType = eventType(order.status());
        if (eventType == null) {
            return Optional.empty();
        }
        String externalOrderNo = clean(order.requestId());
        if (externalOrderNo.isEmpty()) {
            return Optional.empty();
        }
        long timestamp = occurredAt == null ? Instant.now().getEpochSecond() : occurredAt.getEpochSecond();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", externalOrderNo);
        data.put("outTradeNo", clean(order.orderNo()));
        data.put("orderStatus", callbackStatus(order.status()));
        data.put("failReason", safeReason(order.status()));
        data.put("orderCost", money(order.payAmount()));
        data.put("productNo", order.goodsId() == null ? "" : String.valueOf(order.goodsId()));
        data.put("goodsType", order.goodsType() == null ? "" : order.goodsType().name());
        data.put("buyNum", order.quantity() == null ? 0 : order.quantity());

        try {
            String sensitiveJson = OBJECT_MAPPER.writeValueAsString(sensitiveData(order));
            String eventId = eventId(order, sensitiveJson);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("eventType", eventType);
            payload.put("timestamp", timestamp);
            payload.put("data", data);
            return Optional.of(new Event(
                eventId,
                eventType,
                order.status().name(),
                OBJECT_MAPPER.writeValueAsString(payload),
                sensitiveJson
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("member callback serialization failed", ex);
        }
    }

    private static String eventType(OrderStatus status) {
        if (status == null) return null;
        return switch (status) {
            case DELIVERED -> "order.succeeded";
            case FAILED -> "order.failed";
            case CANCELLED -> "order.cancelled";
            case REFUNDED -> "order.refunded";
            case CLOSED -> "order.closed";
            default -> null;
        };
    }

    private static String callbackStatus(OrderStatus status) {
        return switch (status) {
            case DELIVERED -> "SUCCESS";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case REFUNDED -> "REFUNDED";
            case CLOSED -> "CLOSED";
            default -> "PROCESSING";
        };
    }

    private static String safeReason(OrderStatus status) {
        return switch (status) {
            case FAILED -> "order processing failed";
            case CANCELLED -> "order cancelled";
            case REFUNDED -> "order refunded";
            case CLOSED -> "order closed";
            default -> "";
        };
    }

    private static Map<String, Object> sensitiveData(OrderItem order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", order.userId());
        data.put("buyerAccount", clean(order.buyerAccount()));
        data.put("orderIp", clean(order.orderIp()));
        data.put("orderIpLocation", clean(order.orderIpLocation()));
        data.put("goodsName", clean(order.goodsName()));
        data.put("unitPrice", money(order.unitPrice()));
        data.put("rechargeAccount", clean(order.rechargeAccount()));
        data.put("rechargeFields", order.rechargeFields() == null ? Map.of() : order.rechargeFields());
        data.put("buyerRemark", clean(order.buyerRemark()));
        data.put("paymentNo", clean(order.paymentNo()));
        data.put("payMethod", clean(order.payMethod()));
        data.put("deliveryItems", order.deliveryItems() == null ? List.of() : order.deliveryItems());
        data.put("channelAttempts", order.channelAttempts() == null
            ? List.of()
            : order.channelAttempts().stream().map(MemberOrderCallbackPayload::channelAttempt).toList());
        data.put("deliveryMessage", clean(order.deliveryMessage()));
        data.put("upstreamOrderNo", clean(order.upstreamOrderNo()));
        data.put("createdAt", time(order.createdAt()));
        data.put("paidAt", time(order.paidAt()));
        data.put("deliveredAt", time(order.deliveredAt()));
        return data;
    }

    private static Map<String, Object> channelAttempt(ChannelAttemptItem attempt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelId", attempt.channelId());
        data.put("supplierId", attempt.supplierId());
        data.put("supplierName", clean(attempt.supplierName()));
        data.put("supplierGoodsId", clean(attempt.supplierGoodsId()));
        data.put("supplierGoodsName", clean(attempt.supplierGoodsName()));
        data.put("supplierPrice", money(attempt.supplierPrice()));
        data.put("upstreamStatus", clean(attempt.upstreamStatus()));
        data.put("callbackStatus", clean(attempt.callbackStatus()));
        data.put("callbackMessage", clean(attempt.callbackMessage()));
        data.put("rawResponse", clean(attempt.rawResponse()));
        data.put("priority", attempt.priority());
        data.put("status", clean(attempt.status()));
        data.put("message", clean(attempt.message()));
        data.put("attemptedAt", time(attempt.attemptedAt()));
        return data;
    }

    private static String time(java.time.OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }

    private static String eventId(OrderItem order, String sensitiveJson) {
        try {
            String occurrence = order.deliveredAt() == null
                ? "legacy:" + time(order.createdAt()) + ":" + sensitiveJson
                : String.valueOf(order.deliveredAt().toInstant().toEpochMilli());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (clean(order.orderNo()) + "\n" + (order.status() == null ? "" : order.status().name())
                    + "\n" + occurrence)
                    .getBytes(StandardCharsets.UTF_8)
            );
            return "evt_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record Event(
        String eventId,
        String eventType,
        String orderStatus,
        String payloadJson,
        String sensitiveJson
    ) {
    }
}
