package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;

public record MemberApiCredentialItem(
    Long id,
    Long userId,
    String appKey,
    String appSecret,
    String status,
    List<String> ipWhitelist,
    int dailyLimit,
    OffsetDateTime createdAt,
    OffsetDateTime lastUsedAt
) {}
