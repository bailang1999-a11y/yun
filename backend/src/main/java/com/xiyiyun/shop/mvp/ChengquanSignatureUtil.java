package com.xiyiyun.shop.mvp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

public final class ChengquanSignatureUtil {
    private ChengquanSignatureUtil() {
    }

    public static String sign(Map<String, ?> params, String key) {
        String payload = params.entrySet().stream()
            .filter(entry -> !"sign".equals(entry.getKey()))
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> StringUtils.hasText(String.valueOf(entry.getValue()).trim()))
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
            .collect(Collectors.joining("&"));
        return md5(payload + "&key=" + (key == null ? "" : key)).toUpperCase();
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 not available", ex);
        }
    }
}
