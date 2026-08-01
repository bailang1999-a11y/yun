package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgisoSignatureUtilTest {
    @Test
    void matchesThePublishedStandardSupplierExampleIncludingEmptyValues() {
        String secret = "rste57w8rsubsnxsb384ur3u9kn5fzhr0a091b3aa4324435aab703142518a8f7";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "1001");
        payload.put("orderNo", "2023061917481700001");
        payload.put("productNo", "test01");
        payload.put("buyNum", 1);
        payload.put("attach", "{\"account\":\"13888888888\"}");
        payload.put("maxAmount", "");
        payload.put("timestamp", 1687168097L);
        payload.put("callbackUrl", "");
        payload.put("version", "1.0");

        assertThat(AgisoSignatureUtil.sign(payload, secret))
            .isEqualTo("EF387ED4D401A275498A9FCC6C22116B");
    }

    @Test
    void signsEveryNonSignParameterSoFutureExtraFieldsAreNotHardCoded() {
        Map<String, Object> original = new LinkedHashMap<>(Map.of(
            "userId", "1001",
            "timestamp", 1687168097L,
            "version", "1.0"
        ));
        Map<String, Object> extended = new LinkedHashMap<>(original);
        extended.put("futureField", "supported");
        extended.put("emptyField", "");

        assertThat(AgisoSignatureUtil.sign(extended, "merchant-secret"))
            .isNotEqualTo(AgisoSignatureUtil.sign(original, "merchant-secret"));
    }
}
