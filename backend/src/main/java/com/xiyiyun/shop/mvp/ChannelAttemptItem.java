package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ChannelAttemptItem(
    Long channelId,
    Long supplierId,
    String supplierName,
    String supplierGoodsId,
    String supplierGoodsName,
    BigDecimal supplierPrice,
    String upstreamStatus,
    String callbackStatus,
    String callbackMessage,
    String rawResponse,
    Integer priority,
    String status,
    String message,
    OffsetDateTime attemptedAt
) {
    public ChannelAttemptItem(
        Long channelId,
        Long supplierId,
        String supplierName,
        String supplierGoodsId,
        Integer priority,
        String status,
        String message,
        OffsetDateTime attemptedAt
    ) {
        this(
            channelId,
            supplierId,
            supplierName,
            supplierGoodsId,
            null,
            null,
            null,
            null,
            null,
            null,
            priority,
            status,
            message,
            attemptedAt
        );
    }
}
