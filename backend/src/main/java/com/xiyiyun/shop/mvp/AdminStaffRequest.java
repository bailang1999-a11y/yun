package com.xiyiyun.shop.mvp;

import java.util.List;

public record AdminStaffRequest(
    String account,
    String nickname,
    String password,
    String confirmPassword,
    String status,
    List<String> permissions
) {}
