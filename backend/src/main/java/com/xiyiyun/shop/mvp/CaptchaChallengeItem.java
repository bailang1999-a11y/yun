package com.xiyiyun.shop.mvp;

public record CaptchaChallengeItem(
    boolean enabled,
    String provider,
    String appId,
    String scene
) {
}
