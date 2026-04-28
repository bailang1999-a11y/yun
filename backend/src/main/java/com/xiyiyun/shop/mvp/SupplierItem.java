package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SupplierItem(
    Long id,
    String name,
    String baseUrl,
    String appKey,
    String appSecretMasked,
    BigDecimal balance,
    String status,
    String remark,
    OffsetDateTime lastSyncAt
) {
    public SupplierItem withStatus(String nextStatus) {
        return new SupplierItem(id, name, baseUrl, appKey, appSecretMasked, balance, nextStatus, remark, OffsetDateTime.now());
    }

    public SupplierItem withBalance(BigDecimal nextBalance) {
        return new SupplierItem(id, name, baseUrl, appKey, appSecretMasked, nextBalance, status, remark, OffsetDateTime.now());
    }
}
