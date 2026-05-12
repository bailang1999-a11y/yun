package com.xiyiyun.shop.mvp;

public record PasswordChangeRequest(
    String currentPassword,
    String newPassword,
    String confirmPassword
) {}
