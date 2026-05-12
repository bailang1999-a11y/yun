package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.List;

public record PriceTemplateItem(
    String id,
    String name,
    String adjustMode,
    BigDecimal referencePrice,
    List<PriceGroupRateItem> groupRates,
    Boolean enabled
) {
}
