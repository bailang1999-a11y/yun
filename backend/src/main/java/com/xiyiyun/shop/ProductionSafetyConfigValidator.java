package com.xiyiyun.shop;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProductionSafetyConfigValidator {
    private static final String DEFAULT_ADMIN_PASSWORD_BCRYPT = "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq";
    private static final String DEFAULT_PAYMENT_CALLBACK_SECRET = "xiyiyun_mock_payment_secret";
    private static final String DEFAULT_CARD_ENCRYPTION_SECRET = "xiyiyun_dev_card_secret";

    private final Environment environment;
    private final String adminPasswordBcrypt;
    private final String paymentCallbackSecret;
    private final String cardEncryptionSecret;

    public ProductionSafetyConfigValidator(
        Environment environment,
        @Value("${xiyiyun.admin.password-bcrypt:}") String adminPasswordBcrypt,
        @Value("${xiyiyun.payment.callback-secret:}") String paymentCallbackSecret,
        @Value("${xiyiyun.card.encryption-secret:}") String cardEncryptionSecret
    ) {
        this.environment = environment;
        this.adminPasswordBcrypt = adminPasswordBcrypt;
        this.paymentCallbackSecret = paymentCallbackSecret;
        this.cardEncryptionSecret = cardEncryptionSecret;
    }

    @PostConstruct
    public void validate() {
        if (!isProdProfile()) {
            return;
        }
        if (!validBcryptHash(adminPasswordBcrypt) || DEFAULT_ADMIN_PASSWORD_BCRYPT.equals(adminPasswordBcrypt)) {
            throw new IllegalStateException("prod profile requires a valid non-default admin bcrypt password hash");
        }
        if (!validSecret(paymentCallbackSecret) || DEFAULT_PAYMENT_CALLBACK_SECRET.equals(paymentCallbackSecret)) {
            throw new IllegalStateException("prod profile requires a non-default payment callback secret of at least 32 characters");
        }
        if (!validSecret(cardEncryptionSecret) || DEFAULT_CARD_ENCRYPTION_SECRET.equals(cardEncryptionSecret)) {
            throw new IllegalStateException("prod profile requires a non-default card encryption secret of at least 32 characters");
        }
    }

    private boolean isProdProfile() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
    }

    private boolean validBcryptHash(String value) {
        return StringUtils.hasText(value) && value.matches("^\\$2[aby]?\\$\\d{2}\\$.{53}$");
    }

    private boolean validSecret(String value) {
        return StringUtils.hasText(value)
            && value.length() >= 32
            && !value.startsWith("please_")
            && !value.startsWith("change_me");
    }
}
