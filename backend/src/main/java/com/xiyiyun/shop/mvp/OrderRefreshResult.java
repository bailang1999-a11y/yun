package com.xiyiyun.shop.mvp;

public record OrderRefreshResult(
    int total,
    int refreshed,
    int changed,
    int failed,
    String firstError
) {
}
