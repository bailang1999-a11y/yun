package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.List;

public record SupplierPriceTrendItem(
    Long channelId,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    BigDecimal latestUnitPrice,
    String latestDirection,
    List<SupplierPriceTrendPoint> points
) {
    public SupplierPriceTrendItem {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
