package com.xiyiyun.shop.mvp;

import java.util.List;

public record MemberApiCredentialRequest(
    Boolean enabled,
    String appKey,
    String appSecret,
    String callbackUrl,
    Boolean resetSecret,
    List<String> ipWhitelist,
    Integer dailyLimit
) {
    public MemberApiCredentialRequest(
        Boolean enabled,
        String appKey,
        String appSecret,
        Boolean resetSecret,
        List<String> ipWhitelist,
        Integer dailyLimit
    ) {
        this(enabled, appKey, appSecret, null, resetSecret, ipWhitelist, dailyLimit);
    }
}
