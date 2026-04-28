package com.xiyiyun.shop.mvp;

public record PaymentCallbackRequest(
    String paymentNo,
    String orderNo,
    String status,
    String channelTradeNo,
    String signature
) {}
