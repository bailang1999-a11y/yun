package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record GoodsChannelItem(
    Long id,
    Long goodsId,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    Integer priority,
    Integer timeoutSeconds,
    String status,
    OffsetDateTime createdAt
) {
}
