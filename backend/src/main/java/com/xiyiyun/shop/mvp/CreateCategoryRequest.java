package com.xiyiyun.shop.mvp;

public record CreateCategoryRequest(
    Long parentId,
    String name,
    String nickname,
    String icon,
    String iconUrl,
    String customIconUrl,
    Integer sort,
    Boolean enabled,
    String status
) {
}
