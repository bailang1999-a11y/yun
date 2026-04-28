package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record SmsLogItem(
    Long id,
    String orderNo,
    String mobile,
    String templateType,
    String content,
    String status,
    String errorMessage,
    OffsetDateTime createdAt
) {}
