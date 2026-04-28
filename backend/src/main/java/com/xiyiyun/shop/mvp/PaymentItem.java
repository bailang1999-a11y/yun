package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentItem(
    String paymentNo,
    String orderNo,
    Long userId,
    String method,
    BigDecimal amount,
    String status,
    String channelTradeNo,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt
) {}
