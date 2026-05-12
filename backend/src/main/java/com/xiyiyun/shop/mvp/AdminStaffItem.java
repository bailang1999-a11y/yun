package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminStaffItem(
    Long id,
    String account,
    String nickname,
    String status,
    List<String> permissions,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
