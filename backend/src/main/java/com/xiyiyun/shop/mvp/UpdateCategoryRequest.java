package com.xiyiyun.shop.mvp;

public record UpdateCategoryRequest(
    Long parentId,
    String name,
    String nickname,
    String icon,
    Integer sort,
    Boolean enabled,
    String status
) {
}
