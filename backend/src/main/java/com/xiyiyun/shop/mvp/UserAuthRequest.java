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
    String mode,
    /** 用户名（可选），注册时由前端传入，登录时不使用。 */
    String username
) {
}
