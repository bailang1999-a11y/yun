package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PaymentCallbackLogItem;
import com.xiyiyun.shop.mvp.PaymentItem;
import com.xiyiyun.shop.mvp.RefundItem;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import com.xiyiyun.shop.persistence.entity.RefundRecordEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class OrderPersistenceMapper {
    public OrderRecordEntity toOrderRecord(OrderItem order) {
        OrderRecordEntity entity = new OrderRecordEntity();
        entity.setOrderNo(order.orderNo());
        entity.setUserId(order.userId());
        entity.setSourcePlatformCode(order.platform());
        entity.setGoodsId(order.goodsId());
        entity.setGoodsName(order.goodsName());
        entity.setGoodsType(order.goodsType() == null ? null : order.goodsType().name());
        entity.setQuantity(order.quantity());
        entity.setUnitPrice(order.unitPrice());
        entity.setTotalAmount(totalAmount(order));
        entity.setPayAmount(order.payAmount());
        entity.setStatus(order.status() == null ? null : order.status().name());
        entity.setDeliveryStatus(deliveryStatus(order.status()));
        entity.setRechargeAccount(order.rechargeAccount());
        entity.setBuyerRemark(order.buyerRemark());
        entity.setRequestId(order.requestId());
        entity.setPaidAt(order.paidAt());
        entity.setDeliveredAt(order.deliveredAt());
        entity.setClosedAt(closedAt(order.status(), order.deliveredAt()));
        entity.setCreatedAt(order.createdAt());
        return entity;
    }

    public OrderItem toOrderItem(OrderRecordEntity entity) {
        OrderStatus status = parseOrderStatus(entity.getStatus());
        return toOrderItem(entity, null);
    }

    public OrderItem toOrderItem(OrderRecordEntity entity, PaymentRecordEntity payment) {
        OrderStatus status = parseOrderStatus(entity.getStatus());
        return new OrderItem(
            entity.getOrderNo(),
            entity.getUserId(),
            "",
            entity.getGoodsId(),
            entity.getGoodsName(),
            parseGoodsType(entity.getGoodsType()),
            entity.getSourcePlatformCode(),
            entity.getQuantity(),
            entity.getUnitPrice(),
            entity.getPayAmount(),
            status,
            entity.getRechargeAccount(),
            entity.getBuyerRemark(),
            entity.getRequestId(),
            payment == null ? null : payment.getPaymentNo(),
            payment == null ? null : payment.getChannel(),
            List.of(),
            List.of(),
            deliveryMessage(status, entity.getDeliveryStatus()),
            entity.getCreatedAt(),
            entity.getPaidAt(),
            entity.getDeliveredAt()
        );
    }

    public PaymentRecordEntity toPaymentRecord(PaymentItem payment, Long orderId) {
        PaymentRecordEntity entity = new PaymentRecordEntity();
        entity.setPaymentNo(payment.paymentNo());
        entity.setOrderId(orderId);
        entity.setOrderNo(payment.orderNo());
        entity.setUserId(payment.userId());
        entity.setChannel(payment.method());
        entity.setOutTradeNo(payment.channelTradeNo() == null ? payment.paymentNo() : payment.channelTradeNo());
        entity.setAmount(payment.amount());
        entity.setStatus(payment.status());
        entity.setPaidAt(payment.paidAt());
        entity.setCreatedAt(payment.createdAt());
        return entity;
    }

    public PaymentItem toPaymentItem(PaymentRecordEntity entity) {
        return new PaymentItem(
            entity.getPaymentNo(),
            entity.getOrderNo(),
            entity.getUserId(),
            entity.getChannel(),
            entity.getAmount(),
            entity.getStatus(),
            entity.getOutTradeNo(),
            entity.getCreatedAt(),
            entity.getPaidAt()
        );
    }

    public PaymentCallbackLogEntity toPaymentCallbackLog(PaymentCallbackLogItem log) {
        PaymentCallbackLogEntity entity = new PaymentCallbackLogEntity();
        entity.setId(log.id());
        entity.setProvider(log.provider());
        entity.setPaymentNo(log.paymentNo());
        entity.setOrderNo(log.orderNo());
        entity.setCallbackStatus(log.status());
        entity.setChannelTradeNo(log.channelTradeNo());
        entity.setResult(log.result());
        entity.setMessage(log.message());
        entity.setCreatedAt(log.createdAt());
        return entity;
    }

    public PaymentCallbackLogItem toPaymentCallbackLogItem(PaymentCallbackLogEntity entity) {
        return new PaymentCallbackLogItem(
            entity.getId(),
            entity.getProvider(),
            entity.getPaymentNo(),
            entity.getOrderNo(),
            entity.getCallbackStatus(),
            entity.getChannelTradeNo(),
            entity.getResult(),
            entity.getMessage(),
            entity.getCreatedAt()
        );
    }

    public RefundRecordEntity toRefundRecord(RefundItem refund, Long orderId, Long paymentId) {
        RefundRecordEntity entity = new RefundRecordEntity();
        entity.setRefundNo(refund.refundNo());
        entity.setOrderId(orderId);
        entity.setPaymentId(paymentId);
        entity.setOrderNo(refund.orderNo());
        entity.setPaymentNo(refund.paymentNo());
        entity.setUserId(refund.userId());
        entity.setOutRefundNo(refund.refundNo());
        entity.setAmount(refund.amount());
        entity.setReason(refund.reason());
        entity.setStatus(refund.status());
        entity.setRefundedAt(refund.refundedAt());
        entity.setCreatedAt(refund.createdAt());
        return entity;
    }

    public RefundItem toRefundItem(RefundRecordEntity entity) {
        return new RefundItem(
            entity.getRefundNo(),
            entity.getOrderNo(),
            entity.getPaymentNo(),
            entity.getUserId(),
            entity.getAmount(),
            entity.getStatus(),
            entity.getReason(),
            entity.getCreatedAt(),
            entity.getRefundedAt()
        );
    }

    private BigDecimal totalAmount(OrderItem order) {
        if (order.unitPrice() != null && order.quantity() != null) {
            return order.unitPrice().multiply(BigDecimal.valueOf(order.quantity()));
        }
        return order.payAmount();
    }

    private String deliveryStatus(OrderStatus status) {
        if (status == null) {
            return "PENDING";
        }
        return switch (status) {
            case DELIVERED -> "DELIVERED";
            case FAILED -> "FAILED";
            case DELIVERING, PROCURING, WAITING_MANUAL -> "PROCESSING";
            default -> "PENDING";
        };
    }

    private OffsetDateTime closedAt(OrderStatus status, OffsetDateTime fallback) {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.CLOSED || status == OrderStatus.REFUNDED) {
            return fallback;
        }
        return null;
    }

    private GoodsType parseGoodsType(String value) {
        try {
            return value == null ? GoodsType.CARD : GoodsType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return GoodsType.CARD;
        }
    }

    private OrderStatus parseOrderStatus(String value) {
        try {
            return value == null ? OrderStatus.CREATED : OrderStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return OrderStatus.CREATED;
        }
    }

    private String deliveryMessage(OrderStatus status, String deliveryStatus) {
        if (status == OrderStatus.DELIVERED) {
            return "订单已完成";
        }
        if (status == OrderStatus.FAILED) {
            return "订单处理失败";
        }
        if (status == OrderStatus.REFUNDED) {
            return "订单已退款";
        }
        if (status == OrderStatus.CANCELLED || status == OrderStatus.CLOSED) {
            return "订单已关闭";
        }
        return deliveryStatus == null ? "" : deliveryStatus;
    }
}
