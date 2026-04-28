package com.xiyiyun.shop.mvp;

public record LoginRequest(
    String account,
    String password,
    String code
) {}
