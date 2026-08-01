package com.xiyiyun.shop.mvp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

final class MemberCallbackSignature {
    private MemberCallbackSignature() {
    }

    static String sign(String appSecret, long timestamp, String body) {
        if (!StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("member API secret is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("member callback signature failed", ex);
        }
    }
}
