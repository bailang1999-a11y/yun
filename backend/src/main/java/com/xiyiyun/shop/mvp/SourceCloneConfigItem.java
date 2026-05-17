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
    List<String> benefitDurations,
    String benefitType,
    String benefitBrand,
    String coverUrl,
    String description,
    List<String> accountTypes,
    Boolean requireRechargeAccount,
    String priceTemplateId,
    String priceMode,
    java.math.BigDecimal priceCoefficient,
    java.math.BigDecimal priceFixedAdd,
    List<String> availablePlatforms,
    List<String> forbiddenPlatforms,
    Integer priority,
    Integer timeoutSeconds
) {
}
