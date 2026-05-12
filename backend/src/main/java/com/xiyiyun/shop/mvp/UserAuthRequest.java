package com.xiyiyun.shop.mvp;

public record UserAuthRequest(
    String account,
    String password,
    String confirmPassword,
    String code,
    String terminal,
    String sliderToken,
    String captchaTicket,
    String captchaRandstr,
    String mode
) {
}
