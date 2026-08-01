package com.xiyiyun.shop.mvp;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

final class SupplierCallbackUrlResolver {
    private static final Map<SupplierPlatform, String> CALLBACK_PATHS = callbackPaths();
    private static final Set<SupplierPlatform> CALLBACK_SENT_ON_SUBMIT = Set.of(
        SupplierPlatform.FENGZHUSHOU,
        SupplierPlatform.CHENGQUAN,
        SupplierPlatform.FANCHEN,
        SupplierPlatform.JINGZHAO
    );

    private SupplierCallbackUrlResolver() {
    }

    static String effectiveUrl(String publicBaseUrl, SupplierItem supplier) {
        if (supplier == null) {
            return "";
        }
        if (StringUtils.hasText(supplier.callbackUrl())) {
            return normalizedCallbackUrl(supplier.callbackUrl());
        }
        return defaultUrl(publicBaseUrl, supplier);
    }

    static String defaultUrl(String publicBaseUrl, SupplierItem supplier) {
        if (supplier == null || supplier.id() == null) {
            return "";
        }
        SupplierPlatform platform = SupplierPlatform.of(supplier).orElse(null);
        String path = platform == null ? null : CALLBACK_PATHS.get(platform);
        String baseUrl = normalizedBaseUrl(publicBaseUrl);
        return path == null || baseUrl.isEmpty() ? "" : baseUrl + path + supplier.id();
    }

    static boolean callbackSentOnSubmit(String effectiveUrl, SupplierItem supplier) {
        SupplierPlatform platform = SupplierPlatform.of(supplier).orElse(null);
        String path = platform == null ? null : CALLBACK_PATHS.get(platform);
        if (!StringUtils.hasText(effectiveUrl) || !CALLBACK_SENT_ON_SUBMIT.contains(platform)
            || path == null || supplier.id() == null) {
            return false;
        }
        try {
            return (path + supplier.id()).equals(URI.create(effectiveUrl.trim()).getPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static String normalizeCustomUrl(String value) {
        return normalizedCallbackUrl(value);
    }

    private static String normalizedBaseUrl(String value) {
        String normalized = normalizedCallbackUrl(value);
        return normalized.replaceAll("/+$", "");
    }

    private static String normalizedCallbackUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        try {
            URI uri = URI.create(normalized);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && StringUtils.hasText(uri.getHost()) ? normalized : "";
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static Map<SupplierPlatform, String> callbackPaths() {
        Map<SupplierPlatform, String> paths = new EnumMap<>(SupplierPlatform.class);
        paths.put(SupplierPlatform.FULU, "/api/upstream/fulu/callback/");
        paths.put(SupplierPlatform.FENGZHUSHOU, "/api/upstream/fengzhushou/callback/");
        paths.put(SupplierPlatform.CHENGQUAN, "/api/upstream/chengquan/callback/");
        paths.put(SupplierPlatform.FANCHEN, "/api/upstream/fanchen/callback/");
        paths.put(SupplierPlatform.JINGZHAO, "/api/upstream/jingzhao/callback/");
        return Map.copyOf(paths);
    }
}
