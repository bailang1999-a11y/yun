package com.xiyiyun.shop.mvp;

public record GroupRuleItem(
    Long groupId,
    String ruleType,
    Long targetId,
    String targetCode,
    String targetName,
    String permission
) {}
