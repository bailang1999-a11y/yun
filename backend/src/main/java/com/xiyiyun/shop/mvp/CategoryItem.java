package com.xiyiyun.shop.mvp;

public record CategoryItem(
    Long id,
    String name,
    String nickname,
    Long parentId,
    String icon,
    Integer sort,
    Boolean enabled,
    String status,
    Integer level,
    Boolean hasChildren
) {
    public CategoryItem(Long id, String name, Long parentId, Integer sort, Boolean enabled) {
        this(id, name, "", parentId, null, sort, enabled, enabled == null || enabled ? "ENABLED" : "DISABLED", 0, false);
    }
}
