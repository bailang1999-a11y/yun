package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record OrderSummaryItem(
    long total,
    BigDecimal externalAmount,
    long missingExternalAmountCount,
    long activeCount,
    long deliveredCount,
    long failedCount
) {
    public OrderSummaryItem {
        externalAmount = externalAmount == null ? BigDecimal.ZERO : externalAmount;
    }

    public static OrderSummaryItem empty() {
        return new OrderSummaryItem(0, BigDecimal.ZERO, 0, 0, 0, 0);
    }
}
