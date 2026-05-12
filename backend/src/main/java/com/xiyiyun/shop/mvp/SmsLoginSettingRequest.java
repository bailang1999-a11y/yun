package com.xiyiyun.shop.mvp;

import java.util.Map;

public record SmsLoginSettingRequest(
    Boolean enabled,
    Boolean adminLoginEnabled,
    Boolean h5LoginEnabled,
    Boolean webLoginEnabled,
    String provider,
    String adminMobile,
    Integer codeLength,
    Integer ttlSeconds,
    Integer cooldownSeconds,
    Integer maxAttempts,
    Map<String, String> genericConfig,
    Map<String, String> tencentConfig,
    Map<String, String> aliyunConfig
) {
}
