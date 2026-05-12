package com.xiyiyun.shop.mvp;

import java.util.Map;

public record SmsLoginSettingItem(
    boolean enabled,
    boolean adminLoginEnabled,
    boolean h5LoginEnabled,
    boolean webLoginEnabled,
    String provider,
    String adminMobile,
    int codeLength,
    int ttlSeconds,
    int cooldownSeconds,
    int maxAttempts,
    Map<String, String> genericConfig,
    Map<String, String> tencentConfig,
    Map<String, String> aliyunConfig
) {
}
