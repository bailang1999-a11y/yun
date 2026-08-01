package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

final class AgisoSignatureUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgisoSignatureUtil() {
    }

    static String sign(Map<String, ?> values, String merchantSecret) {
        String secret = merchantSecret == null ? "" : merchantSecret;
        String query = values.entrySet().stream()
            .filter(entry -> !"sign".equals(entry.getKey()))
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .map(entry -> entry.getKey() + "=" + value(entry.getValue()))
            .collect(Collectors.joining("&"));
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                .digest((secret + query + secret).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02X", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("unsupported signature value", ex);
            }
        }
        return String.valueOf(value);
    }
}
