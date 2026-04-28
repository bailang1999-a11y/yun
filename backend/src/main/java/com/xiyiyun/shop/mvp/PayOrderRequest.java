package com.xiyiyun.shop.mvp;

public record PayOrderRequest(
    String payMethod,
    String terminal
) {}
