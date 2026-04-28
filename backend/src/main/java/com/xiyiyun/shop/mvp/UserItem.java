package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record UserItem(
    Long id,
    String avatar,
    String mobile,
    String email,
    String nickname,
    Long groupId,
    String groupName,
    BigDecimal balance,
    String status,
    OffsetDateTime createdAt
) {}
