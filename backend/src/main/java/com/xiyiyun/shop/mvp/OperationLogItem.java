package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record OperationLogItem(
    Long id,
    String operator,
    String action,
    String resourceType,
    String resourceId,
    String remark,
    OffsetDateTime createdAt
) {}
