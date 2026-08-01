package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SupplierCallbackUrlResolverTest {

    @Test
    void shouldGenerateJingzhaoCallbackFromPublicBaseUrl() {
        SupplierItem supplier = supplier(20004L, "JINGZHAO", "");

        assertThat(SupplierCallbackUrlResolver.effectiveUrl("https://api.xiyi.co/", supplier))
            .isEqualTo("https://api.xiyi.co/api/upstream/jingzhao/callback/20004");
        assertThat(SupplierCallbackUrlResolver.callbackSentOnSubmit(
            SupplierCallbackUrlResolver.effectiveUrl("https://api.xiyi.co", supplier), supplier
        )).isTrue();
    }

    @Test
    void manualCallbackShouldOverrideGeneratedDefault() {
        SupplierItem supplier = supplier(20004L, "JINGZHAO", "https://callback.example.com/order");

        assertThat(SupplierCallbackUrlResolver.effectiveUrl("https://api.xiyi.co", supplier))
            .isEqualTo("https://callback.example.com/order");
        assertThat(SupplierCallbackUrlResolver.callbackSentOnSubmit(
            SupplierCallbackUrlResolver.effectiveUrl("https://api.xiyi.co", supplier), supplier
        )).isFalse();
    }

    @Test
    void unsupportedPlatformOrInvalidBaseUrlShouldNotGenerateCallback() {
        assertThat(SupplierCallbackUrlResolver.defaultUrl(
            "https://api.xiyi.co", supplier(20001L, "KAKAYUN", "")
        )).isEmpty();
        assertThat(SupplierCallbackUrlResolver.defaultUrl(
            "not-a-url", supplier(20004L, "JINGZHAO", "")
        )).isEmpty();
        assertThat(SupplierCallbackUrlResolver.effectiveUrl(
            "https://api.xiyi.co", supplier(20004L, "JINGZHAO", "not-a-url")
        )).isEmpty();
    }

    private SupplierItem supplier(Long id, String platformType, String callbackUrl) {
        return new SupplierItem(
            id, "supplier", platformType, "https://supplier.example.com", "key", "****",
            "user", "app", "secret", "****", callbackUrl, 15, BigDecimal.TEN,
            "ENABLED", "", null
        );
    }
}
