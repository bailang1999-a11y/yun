package com.xiyiyun.shop.mvp;

import java.util.List;

public record UpdateGroupRulesRequest(
    String ruleType,
    List<GroupRulePatch> rules
) {}
