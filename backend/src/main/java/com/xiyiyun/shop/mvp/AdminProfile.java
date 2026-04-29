package com.xiyiyun.shop.mvp;

import java.util.List;

public record AdminProfile(
    Long id,
    String username,
    String nickname,
    List<String> permissions
) {}
