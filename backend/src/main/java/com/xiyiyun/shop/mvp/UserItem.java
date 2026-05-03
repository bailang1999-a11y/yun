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
    BigDecimal deposit,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime lastLoginAt,
    String realNameType,
    String realName,
    String subjectName,
    String certificateNo,
    String verificationStatus
) {}
