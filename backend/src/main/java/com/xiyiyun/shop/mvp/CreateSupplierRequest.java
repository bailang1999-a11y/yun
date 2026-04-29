package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record CreateSupplierRequest(
    String name,
    String baseUrl,
    String platformType,
    String appKey,
    String appSecret,
    String userId,
    String appId,
    String apiKey,
    String apiKeyMasked,
    String callbackUrl,
    Integer timeoutSeconds,
    BigDecimal balance,
    String status,
    String remark
) {
}
