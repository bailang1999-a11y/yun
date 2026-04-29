package com.xiyiyun.shop.mvp;

public record GroupRulePatch(
    Long targetId,
    String targetCode,
    String permission
) {}
