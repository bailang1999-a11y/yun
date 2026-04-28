package com.xiyiyun.shop.mvp;

public record CreateUserGroupRequest(
    String name,
    String description,
    Boolean defaultGroup,
    String status
) {
}
