package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record RechargeFieldItem(
    Long id,
    String code,
    String label,
    String placeholder,
    String helpText,
    String inputType,
    Boolean required,
    Integer sort,
    Boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
