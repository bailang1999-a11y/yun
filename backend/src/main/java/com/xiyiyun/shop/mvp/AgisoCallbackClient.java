package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class AgisoCallbackClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("agiso-callback");

    private final SupplierHttpClient http;

    AgisoCallbackClient(SupplierHttpClient http) {
        this.http = http;
    }

    void post(String callbackUrl, Map<String, Object> payload, int timeoutSeconds) {
        URI uri = callbackUri(callbackUrl);
        String response = http.post(PROFILE, SupplierHttpRequest.query(
            uri,
            json(payload),
            Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 120))),
            "order callback"
        ));
        try {
            JsonNode root = responseNode(response);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("agiso callback returned invalid JSON object");
            }
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                String message = root.path("message").asText("");
                throw new IllegalStateException("agiso callback rejected: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("agiso callback returned invalid JSON", ex);
        }
    }

    private JsonNode responseNode(String response) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(response);
        if (root != null && root.isTextual()) {
            root = OBJECT_MAPPER.readTree(root.textValue());
        }
        return root;
    }

    void validateCallbackUrl(String callbackUrl) {
        callbackUri(callbackUrl);
    }

    private URI callbackUri(String callbackUrl) {
        if (!StringUtils.hasText(callbackUrl)) {
            throw new IllegalArgumentException("callbackUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("callbackUrl is invalid");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean officialHttpCallback = "http".equalsIgnoreCase(uri.getScheme())
            && ("cb.acpr.agiso.com".equals(host) || "cb-acpr.agiso.com".equals(host));
        boolean allowedHttpsCallback = "https".equalsIgnoreCase(uri.getScheme())
            && ("mai.91kami.com".equals(host) || host.endsWith(".agiso.com"));
        if ((!officialHttpCallback && !allowedHttpsCallback) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("callbackUrl is not allowed");
        }
        return uri;
    }

    private String json(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("callback serialization failed", ex);
        }
    }
}
