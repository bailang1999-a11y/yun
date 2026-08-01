package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.GoodsType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xiyiyun.shop.mvp.ChannelAttemptItem;
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
import java.util.Map;
import org.springframework.util.StringUtils;

public class OrderPersistenceMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChannelAttemptItem>> CHANNEL_ATTEMPT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    public OrderRecordEntity toOrderRecord(OrderItem order) {
        return toOrderRecord(order, null);
    }

    public OrderRecordEntity toOrderRecord(OrderItem order, BigDecimal externalMaxAmount) {
        OrderRecordEntity entity = new OrderRecordEntity();
        entity.setOrderNo(order.orderNo());
        entity.setUserId(order.userId());
        entity.setBuyerAccount(order.buyerAccount());
        entity.setSourcePlatformCode(order.platform());
        entity.setGoodsId(order.goodsId());
        entity.setGoodsName(order.goodsName());
        entity.setGoodsType(order.goodsType() == null ? null : order.goodsType().name());
        entity.setOrderIp(order.orderIp());
        entity.setOrderIpLocation(order.orderIpLocation());
        entity.setQuantity(order.quantity());
        entity.setUnitPrice(order.unitPrice());
        entity.setTotalAmount(totalAmount(order));
        entity.setPayAmount(order.payAmount());
        entity.setExternalMaxAmount(externalMaxAmount);
        entity.setStatus(order.status() == null ? null : order.status().name());
        entity.setDeliveryStatus(deliveryStatus(order.status()));
        entity.setDeliveryMessage(order.deliveryMessage());
        entity.setDeliveryItemsJson(order.goodsType() == GoodsType.CARD ? null : toJson(order.deliveryItems()));
        entity.setChannelAttemptsJson(toJson(order.channelAttempts()));
        entity.setRechargeAccount(order.rechargeAccount());
        entity.setRechargeFieldsJson(toJson(order.rechargeFields()));
        entity.setBuyerRemark(order.buyerRemark());
        entity.setRequestId(order.requestId());
        // 批次3(A4)：空串按 NULL 落库，避免多个订单在 uk_orders_upstream 上撞唯一键。
        entity.setUpstreamOrderNo(StringUtils.hasText(order.upstreamOrderNo()) ? order.upstreamOrderNo().trim() : null);
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
        return toOrderItem(entity, payment, null);
    }

    public OrderItem toOrderItem(OrderRecordEntity entity, PaymentRecordEntity payment, List<String> resolvedDeliveryItems) {
        OrderStatus status = parseOrderStatus(entity.getStatus());
        return new OrderItem(
            entity.getOrderNo(),
            entity.getUserId(),
            entity.getBuyerAccount(),
            entity.getGoodsId(),
            entity.getGoodsName(),
            parseGoodsType(entity.getGoodsType()),
            entity.getSourcePlatformCode(),
            entity.getOrderIp(),
            entity.getOrderIpLocation(),
            entity.getQuantity(),
            entity.getUnitPrice(),
            entity.getPayAmount(),
            status,
            entity.getRechargeAccount(),
            fromStringMapJson(entity.getRechargeFieldsJson()),
            entity.getBuyerRemark(),
            entity.getRequestId(),
            payment == null ? null : payment.getPaymentNo(),
            payment == null ? null : payment.getChannel(),
            resolvedDeliveryItems == null ? fromJson(entity.getDeliveryItemsJson(), STRING_LIST_TYPE) : List.copyOf(resolvedDeliveryItems),
            fromJson(entity.getChannelAttemptsJson(), CHANNEL_ATTEMPT_LIST_TYPE),
            deliveryMessage(status, entity.getDeliveryStatus(), entity.getDeliveryMessage()),
            entity.getCreatedAt(),
            entity.getPaidAt(),
            entity.getDeliveredAt(),
            entity.getUpstreamOrderNo()
        );
    }

    public String toCardIdsJson(List<Long> cardIds) {
        return toJson(cardIds == null ? List.of() : cardIds);
    }

    public PaymentRecordEntity toPaymentRecord(PaymentItem payment, Long orderId) {
        PaymentRecordEntity entity = new PaymentRecordEntity();
        entity.setPaymentNo(payment.paymentNo());
        entity.setOrderId(orderId);
        entity.setOrderNo(payment.orderNo());
        entity.setUserId(payment.userId());
        entity.setChannel(payment.method());
        // 空白也要回退到 paymentNo，不能只判 null：
        // out_trade_no 上有唯一键 uk_payment_out_trade_no(channel, out_trade_no)，
        // 一旦写成空串，同渠道所有待支付流水会全部撞在 ('alipay','') 这一行上，
        // upsert 把旧行的 order_no 改成新订单、却保留旧的 payment_no，
        // 结果两笔支付被合并成一行，钱记到错误的订单上。
        entity.setOutTradeNo(
            payment.channelTradeNo() == null || payment.channelTradeNo().isBlank()
                ? payment.paymentNo()
                : payment.channelTradeNo()
        );
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

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("order snapshot JSON serialization failed", ex);
        }
    }

    private Map<String, String> fromStringMapJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(OBJECT_MAPPER.readValue(value, STRING_MAP_TYPE));
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private <T> T fromJson(String value, TypeReference<T> typeReference) {
        if (value == null || value.isBlank()) {
            return emptyValue(typeReference);
        }
        try {
            return OBJECT_MAPPER.readValue(value, typeReference);
        } catch (Exception ex) {
            return emptyValue(typeReference);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T emptyValue(TypeReference<T> typeReference) {
        return (T) List.of();
    }

    private String deliveryMessage(OrderStatus status, String deliveryStatus, String savedDeliveryMessage) {
        if (savedDeliveryMessage != null && !savedDeliveryMessage.isBlank()) {
            return savedDeliveryMessage;
        }
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
