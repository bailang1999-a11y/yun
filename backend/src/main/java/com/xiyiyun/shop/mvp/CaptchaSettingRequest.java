package com.xiyiyun.shop.mvp;

import java.util.Map;

public record CaptchaSettingRequest(
    Boolean enabled,
    Boolean adminLoginEnabled,
    Boolean h5LoginEnabled,
    Boolean webLoginEnabled,
    String provider,
    Map<String, String> tencentConfig,
    Map<String, String> turnstileConfig,
    Map<String, String> genericConfig
) {
}
