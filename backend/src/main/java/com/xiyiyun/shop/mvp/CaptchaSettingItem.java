package com.xiyiyun.shop.mvp;

import java.util.Map;

public record CaptchaSettingItem(
    boolean enabled,
    boolean adminLoginEnabled,
    boolean h5LoginEnabled,
    boolean webLoginEnabled,
    String provider,
    Map<String, String> tencentConfig,
    Map<String, String> genericConfig
) {
}
