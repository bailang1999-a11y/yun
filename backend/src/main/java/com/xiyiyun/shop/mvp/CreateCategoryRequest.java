package com.xiyiyun.shop.mvp;

public record CreateCategoryRequest(
    Long parentId,
    String name,
    Integer sort,
    Boolean enabled
) {
}
