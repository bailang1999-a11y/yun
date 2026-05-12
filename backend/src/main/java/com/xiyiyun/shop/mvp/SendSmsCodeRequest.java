package com.xiyiyun.shop.mvp;

public record SendSmsCodeRequest(
    String account,
    String terminal,
    String captchaTicket,
    String captchaRandstr,
    String mode
) {
}
