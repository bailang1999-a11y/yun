package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record OpenApiLogItem(
    Long id,
    Long userId,
    String appKey,
    String path,
    String status,
    String message,
    OffsetDateTime createdAt
) {}
