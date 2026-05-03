package com.xiyiyun.shop.mvp;

import java.util.List;

public record UserGroupItem(
    Long id,
    String name,
    String description,
    boolean defaultGroup,
    int userCount,
    String status,
    boolean orderEnabled,
    boolean realNameRequiredForOrder,
    List<GroupRuleItem> rules
) {}
