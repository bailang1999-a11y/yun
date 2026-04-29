package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record CardKindItem(
    Long id,
    String name,
    String type,
    BigDecimal cost
) {
}
