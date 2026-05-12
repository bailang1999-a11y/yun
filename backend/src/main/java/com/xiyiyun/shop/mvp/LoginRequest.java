package com.xiyiyun.shop.mvp;

public record LoginRequest(
    String account,
    String password,
    String code,
    String terminal,
    String sliderToken,
    String captchaTicket,
    String captchaRandstr
) {}
