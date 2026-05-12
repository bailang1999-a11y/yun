package com.xiyiyun.shop.mvp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Map;
import org.springframework.util.StringUtils;

public final class JingzhaoSignatureUtil {
    private JingzhaoSignatureUtil() {
    }

    public static String sign(Map<String, Object> params, String key) {
        StringBuilder builder = new StringBuilder(key == null ? "" : key);
        params.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .filter(entry -> !"sign".equalsIgnoreCase(entry.getKey()))
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> StringUtils.hasText(String.valueOf(entry.getValue())))
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(entry -> builder.append(entry.getKey()).append(entry.getValue()));
        return md5(builder.toString());
    }

    public static String formBody(Map<String, Object> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, value) -> {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(key).append('=').append(value == null ? "" : value);
        });
        return builder.toString();
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("jingzhao signature calculation failed");
        }
    }
}
