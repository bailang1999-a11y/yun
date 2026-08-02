package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class WeComRobotMessageFactory {
    private static final int MAX_MARKDOWN_BYTES = 3900;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WeComRobotMessageFactory() {
    }

    public static List<Notification> from(OrderItem order) {
        if (order == null || order.status() == null || clean(order.orderNo()).isEmpty()) {
            return List.of();
        }
        List<Notification> notifications = new ArrayList<>();
        WeComNotificationEvent event = statusEvent(order.status());
        if (event != null) notifications.add(notification(order, event));
        if (event != WeComNotificationEvent.PAYMENT_SUCCEEDED && paymentSucceeded(order)) {
            notifications.add(notification(order, WeComNotificationEvent.PAYMENT_SUCCEEDED));
        }
        if (isUpstreamException(order)) {
            notifications.add(notification(order, WeComNotificationEvent.UPSTREAM_EXCEPTION));
        }
        return List.copyOf(notifications);
    }

    private static boolean paymentSucceeded(OrderItem order) {
        return order.paidAt() != null
            && !clean(order.paymentNo()).isEmpty()
            && order.status() != OrderStatus.CREATED
            && order.status() != OrderStatus.UNPAID
            && order.status() != OrderStatus.PAYING;
    }

    public static String testMessage() {
        return "## 喜易云 · 测试通知\n"
            + "> 企业微信群机器人连接正常\n"
            + "> 发送时间：" + TIME_FORMAT.format(OffsetDateTime.now());
    }

    private static Notification notification(OrderItem order, WeComNotificationEvent event) {
        StringBuilder message = new StringBuilder();
        message.append("## 喜易云 · ").append(eventLabel(event)).append('\n');
        field(message, "订单状态", statusLabel(order.status()));
        field(message, "本地订单号", order.orderNo());
        field(message, "来源订单号", order.requestId());
        field(message, "上游订单号", order.upstreamOrderNo());
        field(message, "商品", order.goodsName());
        field(message, "商品 ID", order.goodsId());
        field(message, "商品类型", order.goodsType());
        field(message, "购买数量", order.quantity());
        field(message, "订单金额", money(order.payAmount()));
        field(message, "下游实付", money(order.externalMaxAmount()));
        field(message, "来源", order.platform());
        field(message, "用户 ID", order.userId());
        field(message, "下单账号", order.buyerAccount());
        field(message, "充值账号", order.rechargeAccount());
        field(message, "充值字段", rechargeFields(order.rechargeFields()));
        field(message, "支付方式", order.payMethod());
        field(message, "支付单号", order.paymentNo());
        field(message, "处理信息", failureMessage(order));
        if (order.goodsType() != null && "CARD".equals(order.goodsType().name())
            && order.status() == OrderStatus.DELIVERED) {
            field(message, "发货内容", "卡密已发放（卡密正文不发送到群）");
        }
        field(message, "创建时间", time(order.createdAt()));
        field(message, "支付时间", time(order.paidAt()));
        field(message, "完成时间", time(order.deliveredAt()));
        return new Notification(
            eventId(order, event), event, order.orderNo(), truncateUtf8(message.toString(), MAX_MARKDOWN_BYTES)
        );
    }

    private static WeComNotificationEvent statusEvent(OrderStatus status) {
        return switch (status) {
            case CREATED, UNPAID -> WeComNotificationEvent.ORDER_CREATED;
            case PAID -> WeComNotificationEvent.PAYMENT_SUCCEEDED;
            case DELIVERED -> WeComNotificationEvent.DELIVERY_SUCCEEDED;
            case FAILED -> WeComNotificationEvent.DELIVERY_FAILED;
            case REFUNDED, CANCELLED, CLOSED -> WeComNotificationEvent.ORDER_REFUNDED;
            default -> null;
        };
    }

    private static boolean isUpstreamException(OrderItem order) {
        if (order.status() != OrderStatus.PROCURING
            && order.status() != OrderStatus.DELIVERING
            && order.status() != OrderStatus.WAITING_MANUAL) {
            return false;
        }
        ChannelAttemptItem attempt = latestAttempt(order);
        return attempt != null && (failed(attempt.status())
            || failed(attempt.upstreamStatus())
            || failed(attempt.callbackStatus()));
    }

    private static boolean failed(String value) {
        String normalized = clean(value).toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("FAIL") || normalized.contains("ERROR") || normalized.contains("REJECT");
    }

    private static ChannelAttemptItem latestAttempt(OrderItem order) {
        if (order.channelAttempts() == null || order.channelAttempts().isEmpty()) return null;
        return order.channelAttempts().get(order.channelAttempts().size() - 1);
    }

    private static String failureMessage(OrderItem order) {
        if (!clean(order.deliveryMessage()).isEmpty()) return order.deliveryMessage();
        ChannelAttemptItem attempt = latestAttempt(order);
        return attempt == null ? "" : attempt.message();
    }

    private static void field(StringBuilder message, String label, Object value) {
        String text = clean(value == null ? "" : String.valueOf(value));
        if (!text.isEmpty()) message.append("> **").append(label).append("：** ").append(clip(text, 300)).append('\n');
    }

    private static String rechargeFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringJoiner joiner = new StringJoiner("；");
        fields.forEach((key, value) -> joiner.add(clean(key) + "=" + clean(value)));
        return joiner.toString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : "¥" + value.stripTrailingZeros().toPlainString();
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "" : TIME_FORMAT.format(value);
    }

    private static String statusLabel(OrderStatus status) {
        return switch (status) {
            case CREATED -> "已创建";
            case UNPAID -> "待支付";
            case PAYING -> "支付中";
            case PAID -> "已支付";
            case DELIVERING -> "发货中";
            case PROCURING -> "采购中";
            case WAITING_MANUAL -> "等待人工处理";
            case DELIVERED -> "已完成";
            case FAILED -> "失败";
            case REFUNDING -> "退款中";
            case REFUNDED -> "已退款";
            case CANCELLED -> "已取消";
            case CLOSED -> "已关闭";
        };
    }

    private static String eventLabel(WeComNotificationEvent event) {
        return switch (event) {
            case ORDER_CREATED -> "新订单";
            case PAYMENT_SUCCEEDED -> "支付成功";
            case DELIVERY_SUCCEEDED -> "发货成功";
            case DELIVERY_FAILED -> "发货失败";
            case ORDER_REFUNDED -> "退款 / 取消";
            case UPSTREAM_EXCEPTION -> "上游异常";
        };
    }

    private static String eventId(OrderItem order, WeComNotificationEvent event) {
        try {
            String source = clean(order.orderNo()) + "\n" + event.name() + "\n" + eventState(order, event);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return "wcm_" + HexFormat.of().formatHex(digest, 0, 20);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String eventState(OrderItem order, WeComNotificationEvent event) {
        return switch (event) {
            case ORDER_CREATED -> "CREATED";
            case PAYMENT_SUCCEEDED -> "PAID:" + clean(order.paymentNo());
            case DELIVERY_SUCCEEDED -> "DELIVERED";
            case DELIVERY_FAILED -> "FAILED";
            case ORDER_REFUNDED, UPSTREAM_EXCEPTION -> order.status().name();
        };
    }

    private static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int length = maxBytes - 3;
        while (length > 0 && (bytes[length] & 0xC0) == 0x80) length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8) + "...";
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replace("\r", " ").replace("\n", " ");
    }

    public record Notification(
        String eventId,
        WeComNotificationEvent event,
        String orderNo,
        String markdown
    ) {
    }
}
