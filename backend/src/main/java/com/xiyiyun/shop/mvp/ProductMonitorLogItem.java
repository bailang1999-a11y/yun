package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;

public record ProductMonitorLogItem(
    Long id,
    Long channelId,
    Long goodsId,
    String goodsName,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    String result,
    String message,
    List<String> changes,
    OffsetDateTime scannedAt,
    OffsetDateTime nextScanAt
) {
}
