package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record ChannelAttemptItem(
    Long channelId,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    Integer priority,
    String status,
    String message,
    OffsetDateTime attemptedAt
) {
}
