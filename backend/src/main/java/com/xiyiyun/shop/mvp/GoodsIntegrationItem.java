package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record GoodsIntegrationItem(
    String id,
    String platformCode,
    String supplierGoodsId,
    String supplierGoodsName,
    BigDecimal supplierPrice,
    String upstreamStatus,
    Integer upstreamStock,
    String upstreamTitle,
    String lastSyncAt,
    Boolean enabled
) {
}
