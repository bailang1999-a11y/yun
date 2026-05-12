package com.xiyiyun.shop.mvp;

public record AdminUserCredentialRequest(
    String account,
    String nickname,
    String newPassword,
    String confirmPassword
) {}
