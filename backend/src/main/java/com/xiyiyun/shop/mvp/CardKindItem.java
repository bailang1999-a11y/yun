package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record CardKindItem(
    Long id,
    String name,
    String type,
    BigDecimal cost,
    int totalCount,
    int availableCount,
    int usedCount
) {
    public CardKindItem(Long id, String name, String type, BigDecimal cost) {
        this(id, name, type, cost, 0, 0, 0);
    }
}
