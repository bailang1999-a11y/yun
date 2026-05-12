package com.xiyiyun.shop.mvp;

public record AdminCredentialRequest(
    String currentPassword,
    String account,
    String nickname,
    String newPassword,
    String confirmPassword
) {}
