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
    public OrderItem withStatus(OrderStatus nextStatus, String nextDeliveryMessage, OffsetDateTime nextDeliveredAt) {
        return new OrderItem(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
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
            nextDeliveredAt
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
        return new OrderItem(
            orderNo,
            userId,
            buyerAccount,
            goodsId,
            goodsName,
            goodsType,
            platform,
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
            nextDeliveredAt
        );
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
