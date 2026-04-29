package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record CreateSupplierRequest(
    String name,
    String baseUrl,
    String appKey,
    String appSecret,
    BigDecimal balance,
    String status,
    String remark
) {
}
