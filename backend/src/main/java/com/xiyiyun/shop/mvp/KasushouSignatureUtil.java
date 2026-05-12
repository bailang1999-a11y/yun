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

    public static String signRaw(String timestamp, String body, String apiKey) {
        return sha1(timestamp + defaultBody(body) + apiKey);
    }

    public static String jsonBody(Object body) {
        return writeJson(body == null ? Map.of() : body);
    }

    public static String sortedJsonBody(Object body) {
        Object sortedBody = body == null ? Map.of() : sortedValue(body);
        return writeJson(sortedBody);
    }

    public static String sortedJsonBodyWithoutBlankValues(Object body) {
        Object sortedBody = body == null ? Map.of() : sortedValue(body, true, false);
        return writeJson(sortedBody);
    }

    public static String sortedStringJsonBodyWithoutBlankValues(Object body) {
        Object sortedBody = body == null ? Map.of() : sortedValue(body, true, true);
        return writeJson(sortedBody);
    }

    private static String defaultBody(String body) {
        return body == null ? "{}" : body;
    }

    private static String writeJson(Object body) {
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
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

    @SuppressWarnings("unchecked")
    private static Object sortedValue(Object value, boolean omitBlankValues, boolean stringifyScalars) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                Object sortedEntryValue = sortedValue(entry.getValue(), omitBlankValues, stringifyScalars);
                if (omitBlankValues && isBlankValue(sortedEntryValue)) {
                    continue;
                }
                sorted.put(String.valueOf(entry.getKey()), sortedEntryValue);
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> sortedValue(item, omitBlankValues, stringifyScalars))
                .filter(item -> !omitBlankValues || !isBlankValue(item))
                .toList();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sortedList = new ArrayList<>();
            for (Object item : iterable) {
                Object sortedItem = sortedValue(item, omitBlankValues, stringifyScalars);
                if (!omitBlankValues || !isBlankValue(sortedItem)) {
                    sortedList.add(sortedItem);
                }
            }
            return sortedList;
        }
        if (value != null && !isScalar(value)) {
            return sortedValue(OBJECT_MAPPER.convertValue(value, Map.class), omitBlankValues, stringifyScalars);
        }
        if (stringifyScalars && value != null) {
            return String.valueOf(value);
        }
        return value;
    }

    private static boolean isBlankValue(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
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
