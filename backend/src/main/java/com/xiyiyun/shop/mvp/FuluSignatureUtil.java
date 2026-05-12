package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

public final class FuluSignatureUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FuluSignatureUtil() {
    }

    public static String requestSign(Map<String, String> params, String appSecret) {
        try {
            return sign(OBJECT_MAPPER.writeValueAsString(params), appSecret);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize fulu request params", ex);
        }
    }

    public static String responseSign(String result, String appSecret) {
        return sign(result == null ? "" : result, appSecret);
    }

    private static String sign(String value, String appSecret) {
        char[] chars = value.toCharArray();
        Arrays.sort(chars);
        return md5(new String(chars) + (appSecret == null ? "" : appSecret));
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
