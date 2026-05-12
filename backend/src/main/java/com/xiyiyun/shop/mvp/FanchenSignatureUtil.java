package com.xiyiyun.shop.mvp;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

public final class FanchenSignatureUtil {
    private static final Charset GBK = Charset.forName("GBK");

    private FanchenSignatureUtil() {
    }

    public static String sign(Map<String, ?> params, List<String> orderedKeys, String key) {
        StringBuilder builder = new StringBuilder();
        for (String name : orderedKeys) {
            if (!builder.isEmpty()) {
                builder.append("&");
            }
            builder.append(name).append("=").append(params.get(name) == null ? "" : params.get(name));
        }
        builder.append("&key=").append(key == null ? "" : key);
        return md5(builder.toString()).toUpperCase();
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(GBK));
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
