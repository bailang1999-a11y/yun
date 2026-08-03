package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
    Map<String, String> rechargeFields,
    String buyerRemark,
    String requestId,
    String paymentNo,
    String payMethod,
    List<String> deliveryItems,
    List<ChannelAttemptItem> channelAttempts,
    String deliveryMessage,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt,
    OffsetDateTime deliveredAt,
    String upstreamOrderNo,
    BigDecimal externalMaxAmount,
    Long averageRechargeDurationSeconds,
    Integer todaySuccessRatePercentage,
    SupplierPriceTrendItem supplierPriceTrend
) {
    public OrderItem {
        rechargeFields = rechargeFields == null ? Map.of() : Map.copyOf(rechargeFields);
        upstreamOrderNo = upstreamOrderNo == null ? "" : upstreamOrderNo.trim();
    }

    public OrderItem(
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
        Map<String, String> rechargeFields,
        String buyerRemark,
        String requestId,
        String paymentNo,
        String payMethod,
        List<String> deliveryItems,
        List<ChannelAttemptItem> channelAttempts,
        String deliveryMessage,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime deliveredAt,
        String upstreamOrderNo,
        BigDecimal externalMaxAmount
    ) {
        this(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            upstreamOrderNo, externalMaxAmount, null, null, null
        );
    }

    public OrderItem(
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
        Map<String, String> rechargeFields,
        String buyerRemark,
        String requestId,
        String paymentNo,
        String payMethod,
        List<String> deliveryItems,
        List<ChannelAttemptItem> channelAttempts,
        String deliveryMessage,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime deliveredAt,
        String upstreamOrderNo
    ) {
        this(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            upstreamOrderNo, null
        );
    }

    /**
     * 批次3(A4)：兼容原 26 参构造，upstreamOrderNo 默认空串。
     * 保证既有调用点（含测试）零改动，只有真正拿到上游订单号的路径才调用 {@link #withUpstreamOrderNo(String)}。
     */
    public OrderItem(
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
        Map<String, String> rechargeFields,
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
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            ""
        );
    }

    /** 批次3(A4)：记录上游订单号，用于超时未知场景的对账与幂等补单。 */
    public OrderItem withUpstreamOrderNo(String nextUpstreamOrderNo) {
        return new OrderItem(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            nextUpstreamOrderNo, externalMaxAmount, averageRechargeDurationSeconds, todaySuccessRatePercentage,
            supplierPriceTrend
        );
    }

    public OrderItem withOrderPerformance(Long averageSeconds, Integer successRatePercentage) {
        return new OrderItem(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            upstreamOrderNo, externalMaxAmount, averageSeconds, successRatePercentage, supplierPriceTrend
        );
    }

    public OrderItem withSupplierPriceTrend(SupplierPriceTrendItem trend) {
        return new OrderItem(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            upstreamOrderNo, externalMaxAmount, averageRechargeDurationSeconds, todaySuccessRatePercentage, trend
        );
    }

    public OrderItem withChannelAttempts(List<ChannelAttemptItem> attempts) {
        return new OrderItem(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, rechargeFields, buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, attempts, deliveryMessage, createdAt, paidAt, deliveredAt,
            upstreamOrderNo, externalMaxAmount, averageRechargeDurationSeconds, todaySuccessRatePercentage,
            supplierPriceTrend
        );
    }

    public OrderItem(
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
        this(
            orderNo, userId, buyerAccount, goodsId, goodsName, goodsType, platform, orderIp, orderIpLocation,
            quantity, unitPrice, payAmount, status, rechargeAccount, Map.of(), buyerRemark, requestId,
            paymentNo, payMethod, deliveryItems, channelAttempts, deliveryMessage, createdAt, paidAt, deliveredAt
        );
    }

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
            Map.of(),
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
            rechargeFields,
            buyerRemark,
            requestId,
            paymentNo,
            payMethod,
            deliveryItems,
            channelAttempts,
            nextDeliveryMessage,
            createdAt,
            paidAt,
            resolvedDeliveredAt,
            upstreamOrderNo,
            externalMaxAmount,
            averageRechargeDurationSeconds,
            todaySuccessRatePercentage,
            supplierPriceTrend
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
            rechargeFields,
            buyerRemark,
            requestId,
            paymentNo,
            payMethod,
            nextDeliveryItems,
            nextChannelAttempts,
            nextDeliveryMessage,
            createdAt,
            nextPaidAt,
            resolvedDeliveredAt,
            upstreamOrderNo,
            externalMaxAmount,
            averageRechargeDurationSeconds,
            todaySuccessRatePercentage,
            supplierPriceTrend
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
            rechargeFields,
            buyerRemark,
            requestId,
            nextPaymentNo,
            nextPayMethod,
            deliveryItems,
            channelAttempts,
            deliveryMessage,
            createdAt,
            paidAt,
            deliveredAt,
            upstreamOrderNo,
            externalMaxAmount,
            averageRechargeDurationSeconds,
            todaySuccessRatePercentage,
            supplierPriceTrend
        );
    }
}
