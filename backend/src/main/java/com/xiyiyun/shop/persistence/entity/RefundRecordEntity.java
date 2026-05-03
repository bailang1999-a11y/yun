package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@TableName("refund_records")
public class RefundRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private Long orderId;
    private Long paymentId;
    private String orderNo;
    private String paymentNo;
    private Long userId;
    private String outRefundNo;
    private BigDecimal amount;
    private String reason;
    private String status;
    private String channelPayload;
    private OffsetDateTime refundedAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOutRefundNo() { return outRefundNo; }
    public void setOutRefundNo(String outRefundNo) { this.outRefundNo = outRefundNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannelPayload() { return channelPayload; }
    public void setChannelPayload(String channelPayload) { this.channelPayload = channelPayload; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
