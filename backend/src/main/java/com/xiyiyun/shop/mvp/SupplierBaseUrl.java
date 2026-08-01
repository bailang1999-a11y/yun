package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;

import java.net.URI;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 占位地址识别。原 {@code InMemoryShopRepository#isPlaceholderBaseUrl} 原样搬出，
 * 判定规则零改动，仅为让适配器与仓储共用一份实现。
 */
final class SupplierBaseUrl {

    private SupplierBaseUrl() {
    }

    static boolean isPlaceholder(String baseUrl) {
        String trimmed = defaultText(baseUrl, "").trim();
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.contains("example") || normalized.contains("你的") || normalized.contains("占位")) {
            return true;
        }
        try {
            String host = URI.create(trimmed).getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return "example.com".equals(normalizedHost)
                || normalizedHost.endsWith(".example.com")
                || normalizedHost.endsWith(".example")
                || normalizedHost.contains(".example.");
        } catch (Exception ex) {
            return false;
        }
    }
}
