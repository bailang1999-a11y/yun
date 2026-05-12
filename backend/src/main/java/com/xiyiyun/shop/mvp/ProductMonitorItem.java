package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record ProductMonitorItem(
    Long channelId,
    Long goodsId,
    String goodsName,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    Boolean primaryChannel,
    String status,
    OffsetDateTime lastScanAt,
    OffsetDateTime nextScanAt,
    String lastResult,
    String lastMessage,
    Integer scanCount,
    Integer changeCount
) {
}
