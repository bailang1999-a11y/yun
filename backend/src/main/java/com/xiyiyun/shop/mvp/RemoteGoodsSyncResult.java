package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record RemoteGoodsSyncResult(
    Long supplierId,
    OffsetDateTime syncedAt,
    Integer total,
    List<RemoteGoodsItem> items,
    List<Map<String, Object>> categories,
    Integer page,
    Integer limit,
    String summary
) {
}
