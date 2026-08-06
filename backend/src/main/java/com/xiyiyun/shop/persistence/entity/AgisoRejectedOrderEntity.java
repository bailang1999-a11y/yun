package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@TableName("agiso_rejected_orders")
public class AgisoRejectedOrderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String displayOrderNo;
    private String protocol;
    private Long userId;
    private String buyerAccount;
    private String externalOrderNo;
    private Long productNo;
    private String goodsName;
    private String goodsType;
    private Integer quantity;
    private BigDecimal externalMaxAmount;
    private BigDecimal expectedAmount;
    private String rechargeAccount;
    private String rechargeFieldsJson;
    private String callbackUrl;
    private String rejectCode;
    private String rejectReason;
    private Integer attemptCount;
    private String state;
    private String resolvedOrderNo;
    private OffsetDateTime rejectedAt;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDisplayOrderNo() { return displayOrderNo; }
    public void setDisplayOrderNo(String displayOrderNo) { this.displayOrderNo = displayOrderNo; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBuyerAccount() { return buyerAccount; }
    public void setBuyerAccount(String buyerAccount) { this.buyerAccount = buyerAccount; }
    public String getExternalOrderNo() { return externalOrderNo; }
    public void setExternalOrderNo(String externalOrderNo) { this.externalOrderNo = externalOrderNo; }
    public Long getProductNo() { return productNo; }
    public void setProductNo(Long productNo) { this.productNo = productNo; }
    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }
    public String getGoodsType() { return goodsType; }
    public void setGoodsType(String goodsType) { this.goodsType = goodsType; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getExternalMaxAmount() { return externalMaxAmount; }
    public void setExternalMaxAmount(BigDecimal externalMaxAmount) { this.externalMaxAmount = externalMaxAmount; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }
    public String getRechargeAccount() { return rechargeAccount; }
    public void setRechargeAccount(String rechargeAccount) { this.rechargeAccount = rechargeAccount; }
    public String getRechargeFieldsJson() { return rechargeFieldsJson; }
    public void setRechargeFieldsJson(String rechargeFieldsJson) { this.rechargeFieldsJson = rechargeFieldsJson; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getRejectCode() { return rejectCode; }
    public void setRejectCode(String rejectCode) { this.rejectCode = rejectCode; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getResolvedOrderNo() { return resolvedOrderNo; }
    public void setResolvedOrderNo(String resolvedOrderNo) { this.resolvedOrderNo = resolvedOrderNo; }
    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(OffsetDateTime rejectedAt) { this.rejectedAt = rejectedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
