package com.xiyiyun.shop.mvp;

public record AuthSession<T>(
    String token,
    T profile
) {}
