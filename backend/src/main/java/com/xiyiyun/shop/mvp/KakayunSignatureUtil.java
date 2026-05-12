package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KakayunSignatureUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private KakayunSignatureUtil() {
    }

    public static String sign(Map<String, Object> params, String merchantKey) {
        String source = sortedQuery(params) + (merchantKey == null ? "" : merchantKey);
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 not available", ex);
        }
    }

    public static String jsonBody(Map<String, Object> params) {
        try {
            return OBJECT_MAPPER.writeValueAsString(params == null ? Map.of() : params);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize kakayun request body", ex);
        }
    }

    private static String sortedQuery(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || "sign".equals(key) || isBlank(value)) {
                continue;
            }
            entries.add(Map.entry(key, value));
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : entries) {
            parts.add(entry.getKey() + "=" + valueText(entry.getValue()));
        }
        return String.join("&", parts);
    }

    @SuppressWarnings("unchecked")
    private static String valueText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> sorted.put(String.valueOf(entry.getKey()), entry.getValue()));
            return jsonBody(sorted);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : iterable) {
                values.add(item instanceof Map<?, ?> ? OBJECT_MAPPER.convertValue(item, Map.class) : item);
            }
            return jsonBody(Map.of("value", values)).replaceFirst("^\\{\"value\":", "").replaceFirst("}$", "");
        }
        return String.valueOf(value);
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }
}
