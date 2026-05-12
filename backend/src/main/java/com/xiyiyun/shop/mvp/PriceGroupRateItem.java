package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record PriceGroupRateItem(
    String groupName,
    String color,
    BigDecimal value
) {
}
