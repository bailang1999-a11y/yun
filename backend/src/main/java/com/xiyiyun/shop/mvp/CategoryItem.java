package com.xiyiyun.shop.mvp;

public record CategoryItem(
    Long id,
    String name,
    Long parentId,
    Integer sort,
    Boolean enabled
) {
}
