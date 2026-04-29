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

public final class KasushouSignatureUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private KasushouSignatureUtil() {
    }

    public static Map<String, String> headers(String timestamp, String userId, Object body, String apiKey) {
        return Map.of(
            "Sign", sign(timestamp, body, apiKey),
            "Timestamp", timestamp,
            "UserId", userId
        );
    }

    public static String sign(String timestamp, Object body, String apiKey) {
        return sha1(timestamp + sortedJsonBody(body) + apiKey);
    }

    public static String sortedJsonBody(Object body) {
        Object sortedBody = body == null ? Map.of() : sortedValue(body);
        try {
            return OBJECT_MAPPER.writeValueAsString(sortedBody);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize kasushou request body", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                sorted.put(String.valueOf(entry.getKey()), sortedValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(KasushouSignatureUtil::sortedValue).toList();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sortedList = new ArrayList<>();
            for (Object item : iterable) {
                sortedList.add(sortedValue(item));
            }
            return sortedList;
        }
        if (value != null && !isScalar(value)) {
            return sortedValue(OBJECT_MAPPER.convertValue(value, Map.class));
        }
        return value;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Character
            || value instanceof Enum<?>;
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 algorithm is not available", ex);
        }
    }
}
