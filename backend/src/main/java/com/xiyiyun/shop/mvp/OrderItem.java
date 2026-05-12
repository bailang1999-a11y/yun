package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderItem(
    String orderNo,
    Long userId,
    String buyerAccount,
    Long goodsId,
    String goodsName,
    GoodsType goodsType,
    String platform,
    String orderIp,
    String orderIpLocation,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal payAmount,
    OrderStatus status,
    String rechargeAccount,
    String buyerRemark,
    String requestId,
    String paymentNo,
    String payMethod,
    List<String> deliveryItems,
    List<ChannelAttemptItem> channelAttempts,
    String deliveryMessage,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt,
    OffsetDateTime deliveredAt
) {
    public OrderItem(
        String orderNo,
        Long userId,
        String buyerAccount,
        Long goodsId,
        String goodsName,
        GoodsType goodsType,
        String platform,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal payAmount,
        OrderStatus status,
        String rechargeAccount,
        String buyerRemark,
        String requestId,
        String paymentNo,
        String payMethod,
        List<String> deliveryItems,
        List<ChannelAttemptItem> channelAttempts,
        String deliveryMessage,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime deliveredAt
    ) {
        this(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
            "",
            "",
            quantity,
            unitPrice,
            payAmount,
            status,
            rechargeAccount,
            buyerRemark,
            requestId,
            paymentNo,
            payMethod,
            deliveryItems,
            channelAttempts,
            deliveryMessage,
            createdAt,
            paidAt,
            deliveredAt
        );
    }

    public OrderItem withStatus(OrderStatus nextStatus, String nextDeliveryMessage, OffsetDateTime nextDeliveredAt) {
        OffsetDateTime resolvedDeliveredAt = resolveTerminalTime(nextStatus, nextDeliveredAt);
        return new OrderItem(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
            orderIp,
            orderIpLocation,
            quantity,
            unitPrice,
            payAmount,
            nextStatus,
            rechargeAccount,
            buyerRemark,
            requestId,
            paymentNo,
            payMethod,
            deliveryItems,
            channelAttempts,
            nextDeliveryMessage,
            createdAt,
            paidAt,
            resolvedDeliveredAt
        );
    }

    public OrderItem withProcurementResult(
        OrderStatus nextStatus,
        List<String> nextDeliveryItems,
        List<ChannelAttemptItem> nextChannelAttempts,
        String nextDeliveryMessage,
        OffsetDateTime nextPaidAt,
        OffsetDateTime nextDeliveredAt
    ) {
        OffsetDateTime resolvedDeliveredAt = resolveTerminalTime(nextStatus, nextDeliveredAt);
        return new OrderItem(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
            orderIp,
            orderIpLocation,
            quantity,
            unitPrice,
            payAmount,
            nextStatus,
            rechargeAccount,
            buyerRemark,
            requestId,
            paymentNo,
            payMethod,
            nextDeliveryItems,
            nextChannelAttempts,
            nextDeliveryMessage,
            createdAt,
            nextPaidAt,
            resolvedDeliveredAt
        );
    }

    private static OffsetDateTime resolveTerminalTime(OrderStatus status, OffsetDateTime deliveredAt) {
        if (deliveredAt != null || status == null) {
            return deliveredAt;
        }
        return switch (status) {
            case DELIVERED, FAILED, REFUNDED, CANCELLED, CLOSED -> OffsetDateTime.now();
            default -> null;
        };
    }

    public OrderItem withPayment(String nextPaymentNo, String nextPayMethod) {
        return new OrderItem(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
            orderIp,
            orderIpLocation,
            quantity,
            unitPrice,
            payAmount,
            status,
            rechargeAccount,
            buyerRemark,
            requestId,
            nextPaymentNo,
            nextPayMethod,
            deliveryItems,
            channelAttempts,
            deliveryMessage,
            createdAt,
            paidAt,
            deliveredAt
        );
    }
}
