package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record PaymentCallbackLogItem(
    Long id,
    String provider,
    String paymentNo,
    String orderNo,
    String status,
    String channelTradeNo,
    String result,
    String message,
    OffsetDateTime createdAt
) {}
