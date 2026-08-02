package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record WeComRobotDeliveryItem(
    Long id,
    String event,
    String status,
    String orderNo,
    int attemptCount,
    String errorMessage,
    OffsetDateTime createdAt
) {
}
