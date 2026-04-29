package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RefundItem(
    String refundNo,
    String orderNo,
    String paymentNo,
    Long userId,
    BigDecimal amount,
    String status,
    String reason,
    OffsetDateTime createdAt,
    OffsetDateTime refundedAt
) {}
