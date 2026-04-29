package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SupplierItem(
    Long id,
    String name,
    String platformType,
    String baseUrl,
    String appKey,
    String appSecretMasked,
    String userId,
    String appId,
    String apiKeyMasked,
    String callbackUrl,
    Integer timeoutSeconds,
    BigDecimal balance,
    String status,
    String remark,
    OffsetDateTime lastSyncAt
) {
    public SupplierItem withStatus(String nextStatus) {
        return new SupplierItem(id, name, platformType, baseUrl, appKey, appSecretMasked, userId, appId, apiKeyMasked, callbackUrl, timeoutSeconds, balance, nextStatus, remark, OffsetDateTime.now());
    }

    public SupplierItem withBalance(BigDecimal nextBalance) {
        return new SupplierItem(id, name, platformType, baseUrl, appKey, appSecretMasked, userId, appId, apiKeyMasked, callbackUrl, timeoutSeconds, nextBalance, status, remark, OffsetDateTime.now());
    }

    public SupplierItem withLastSyncAt(OffsetDateTime nextLastSyncAt) {
        return new SupplierItem(id, name, platformType, baseUrl, appKey, appSecretMasked, userId, appId, apiKeyMasked, callbackUrl, timeoutSeconds, balance, status, remark, nextLastSyncAt);
    }
}
