package com.xiyiyun.shop.mvp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

public final class FengzhushouSignatureUtil {
    private FengzhushouSignatureUtil() {
    }

    public static String sign(Map<String, ?> params, String signKey) {
        String payload = params.entrySet().stream()
            .filter(entry -> !"sign".equals(entry.getKey()))
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> StringUtils.hasText(String.valueOf(entry.getValue()).trim()))
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
            .collect(Collectors.joining("&"));
        String signTemp = payload + (payload.isEmpty() ? "" : "&") + "signKey=" + (signKey == null ? "" : signKey);
        return sha1(signTemp).toUpperCase();
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 not available", ex);
        }
    }
}
