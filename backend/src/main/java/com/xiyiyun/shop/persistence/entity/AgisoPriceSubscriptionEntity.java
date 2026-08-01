package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@TableName("agiso_price_subscriptions")
public class AgisoPriceSubscriptionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String platformUserId;
    private String supplierAccountGuid;
    private Long productNo;
    private String state;
    private BigDecimal lastNotifiedPrice;
    private Long lastPriceVer;
    private OffsetDateTime nextCheckAt;
    private OffsetDateTime leaseUntil;
    private String leaseToken;
    private Integer attemptCount;
    private String lastError;
    private OffsetDateTime lastNotifiedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPlatformUserId() { return platformUserId; }
    public void setPlatformUserId(String platformUserId) { this.platformUserId = platformUserId; }
    public String getSupplierAccountGuid() { return supplierAccountGuid; }
    public void setSupplierAccountGuid(String supplierAccountGuid) { this.supplierAccountGuid = supplierAccountGuid; }
    public Long getProductNo() { return productNo; }
    public void setProductNo(Long productNo) { this.productNo = productNo; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public BigDecimal getLastNotifiedPrice() { return lastNotifiedPrice; }
    public void setLastNotifiedPrice(BigDecimal lastNotifiedPrice) { this.lastNotifiedPrice = lastNotifiedPrice; }
    public Long getLastPriceVer() { return lastPriceVer; }
    public void setLastPriceVer(Long lastPriceVer) { this.lastPriceVer = lastPriceVer; }
    public OffsetDateTime getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(OffsetDateTime nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public OffsetDateTime getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(OffsetDateTime lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
