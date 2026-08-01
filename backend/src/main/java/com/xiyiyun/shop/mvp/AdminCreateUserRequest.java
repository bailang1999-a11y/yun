package com.xiyiyun.shop.mvp;

public record AdminCreateUserRequest(
    String account,
    String nickname,
    String password,
    String confirmPassword
) {
}
