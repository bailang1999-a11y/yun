package com.xiyiyun.shop.mvp;

import java.util.List;

public record MemberApiCredentialRequest(
    Boolean enabled,
    String appKey,
    String appSecret,
    Boolean resetSecret,
    List<String> ipWhitelist,
    Integer dailyLimit
) {
}
