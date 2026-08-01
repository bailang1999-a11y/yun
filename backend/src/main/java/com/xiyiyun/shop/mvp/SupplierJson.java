package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 适配器共用的 JSON 取值助手。
 *
 * <p>逐行复制 InMemoryShopRepository 里同名私有方法的实现，保持取值语义完全一致
 * （包括「空串视为缺失」「NumberFormatException 返回 null」等边界行为）。</p>
 */
public final class SupplierJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private SupplierJson() {
    }

    public static JsonNode firstExisting(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    public static String textValue(JsonNode node, String... fieldNames) {
        JsonNode value = firstExisting(node, fieldNames);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    public static BigDecimal optionalDecimalValue(JsonNode node, String... fieldNames) {
        JsonNode value = firstExisting(node, fieldNames);
        if (value == null || value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static BigDecimal decimalValue(JsonNode node, String... fieldNames) {
        BigDecimal value = optionalDecimalValue(node, fieldNames);
        return value == null ? BigDecimal.ZERO : value;
    }

    public static int intValue(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    public static String defaultText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public static String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return fallback;
    }

    public static String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    public static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static Map<String, Object> mutableMap(Map<String, Object> source) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (source != null) {
            body.putAll(source);
        }
        return body;
    }
}
