package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record CreateCardKindRequest(
    String name,
    String type,
    BigDecimal cost
) {
}
