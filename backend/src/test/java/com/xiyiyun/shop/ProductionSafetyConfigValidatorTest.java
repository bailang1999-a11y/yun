package com.xiyiyun.shop;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyConfigValidatorTest {
    private static final String ADMIN_HASH = "$2y$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuuabcdefghi";
    private static final String CALLBACK_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String CARD_SECRET = "abcdef0123456789abcdef0123456789";

    @Test
    void prodRejectsWildcardCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("*");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    void prodRejectsExampleCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("https://www.example.com");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    void prodRejectsPlaceholderCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("https://shop.your-domain.com");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    void prodRejectsHttpCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("http://shop.xiyiyun.com");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    void prodRejectsLocalhostCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("https://localhost:5175");

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORS");
    }

    @Test
    void prodAcceptsExplicitHttpsCorsOrigins() {
        ProductionSafetyConfigValidator validator = prodValidator("https://shop.xiyiyun.com,https://admin.xiyiyun.com");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void nonProdRejectsProductionCorsOrigins() {
        MockEnvironment environment = new MockEnvironment();
        ProductionSafetyConfigValidator validator = new ProductionSafetyConfigValidator(
            environment,
            ADMIN_HASH,
            CALLBACK_SECRET,
            CARD_SECRET,
            "https://shop.xiyiyun.com"
        );

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prod profile");
    }

    @Test
    void nonProdAllowsLocalWildcardCors() {
        MockEnvironment environment = new MockEnvironment();
        ProductionSafetyConfigValidator validator = new ProductionSafetyConfigValidator(
            environment,
            "",
            "",
            "",
            "*"
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void prodRejectsDefaultPaymentCallbackSecret() {
        ProductionSafetyConfigValidator validator = prodValidator(
            ADMIN_HASH,
            "xiyiyun_mock_payment_secret",
            CARD_SECRET,
            "https://shop.xiyiyun.com"
        );

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("payment callback secret");
    }

    @Test
    void prodRejectsDefaultCardEncryptionSecret() {
        ProductionSafetyConfigValidator validator = prodValidator(
            ADMIN_HASH,
            CALLBACK_SECRET,
            "xiyiyun_dev_card_secret",
            "https://shop.xiyiyun.com"
        );

        assertThatThrownBy(validator::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("card encryption secret");
    }

    private ProductionSafetyConfigValidator prodValidator(String corsAllowedOrigins) {
        return prodValidator(ADMIN_HASH, CALLBACK_SECRET, CARD_SECRET, corsAllowedOrigins);
    }

    private ProductionSafetyConfigValidator prodValidator(
        String adminPasswordBcrypt,
        String paymentCallbackSecret,
        String cardEncryptionSecret,
        String corsAllowedOrigins
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new ProductionSafetyConfigValidator(
            environment,
            adminPasswordBcrypt,
            paymentCallbackSecret,
            cardEncryptionSecret,
            corsAllowedOrigins
        );
    }
}
