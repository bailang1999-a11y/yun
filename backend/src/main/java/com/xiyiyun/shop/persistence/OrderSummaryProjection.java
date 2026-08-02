package com.xiyiyun.shop.persistence;

import java.math.BigDecimal;

public class OrderSummaryProjection {
    private Long total;
    private BigDecimal externalAmount;
    private Long missingExternalAmountCount;
    private Long activeCount;
    private Long deliveredCount;
    private Long failedCount;

    public Long getTotal() { return total; }
    public void setTotal(Long total) { this.total = total; }
    public BigDecimal getExternalAmount() { return externalAmount; }
    public void setExternalAmount(BigDecimal externalAmount) { this.externalAmount = externalAmount; }
    public Long getMissingExternalAmountCount() { return missingExternalAmountCount; }
    public void setMissingExternalAmountCount(Long value) { this.missingExternalAmountCount = value; }
    public Long getActiveCount() { return activeCount; }
    public void setActiveCount(Long activeCount) { this.activeCount = activeCount; }
    public Long getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(Long deliveredCount) { this.deliveredCount = deliveredCount; }
    public Long getFailedCount() { return failedCount; }
    public void setFailedCount(Long failedCount) { this.failedCount = failedCount; }
}
