package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record OutboundProtocolSettings(
    boolean enabled,
    String baseUrl,
    int requestLimitPerMinute,
    int timeoutSeconds,
    Map<String, Boolean> protocols,
    String exposureMode,
    List<Long> categoryIds,
    List<Long> goodsIds,
    List<String> deliveryTypes,
    String pricePolicy,
    BigDecimal priceAdjustment,
    boolean includeDisabledGoods
) {
}
