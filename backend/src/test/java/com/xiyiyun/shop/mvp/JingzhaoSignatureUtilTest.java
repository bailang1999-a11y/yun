package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JingzhaoSignatureUtilTest {

    @Test
    void emptyCardsMustBeExcludedFromCallbackSignature() {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("customer_id", "10001");
        callback.put("outer_order_id", "ORDER-1");
        callback.put("state", "100");
        callback.put("cards", "[]");

        Map<String, Object> signedFields = new LinkedHashMap<>(callback);
        signedFields.remove("cards");

        assertThat(JingzhaoSignatureUtil.callbackSign(callback, "secret"))
            .isEqualTo(JingzhaoSignatureUtil.sign(signedFields, "secret"));
    }

    @Test
    void nonEmptyCardsMustRemainSigned() {
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("customer_id", "10001");
        callback.put("cards", "[{\"cardNo\":\"A1\"}]");

        assertThat(JingzhaoSignatureUtil.callbackSign(callback, "secret"))
            .isEqualTo(JingzhaoSignatureUtil.sign(callback, "secret"));
    }
}
