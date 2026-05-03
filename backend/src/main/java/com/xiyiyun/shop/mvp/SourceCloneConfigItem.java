package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.List;

public record SourceCloneConfigItem(
    String supplierGoodsId,
    String name,
    Long categoryId,
    BigDecimal price,
    BigDecimal originalPrice,
    Integer stock,
    String status,
    String coverUrl,
    String description,
    List<String> accountTypes,
    Boolean requireRechargeAccount,
    Integer priority,
    Integer timeoutSeconds
) {
}
