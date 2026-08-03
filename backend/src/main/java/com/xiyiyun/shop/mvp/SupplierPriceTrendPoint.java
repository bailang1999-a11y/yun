package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SupplierPriceTrendPoint(
    BigDecimal unitPrice,
    BigDecimal changeAmount,
    String direction,
    OffsetDateTime observedAt
) {
}
