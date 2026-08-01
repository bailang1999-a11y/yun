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
    private final String corsAllowedOrigins;
    private final boolean paymentCallbackStrictSignature;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionSafetyConfigValidator(
        Environment environment,
        @Value("${xiyiyun.admin.password-bcrypt:}") String adminPasswordBcrypt,
        @Value("${xiyiyun.payment.callback-secret:}") String paymentCallbackSecret,
        @Value("${xiyiyun.card.encryption-secret:}") String cardEncryptionSecret,
        @Value("${xiyiyun.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${xiyiyun.payment.callback-strict-signature:false}") boolean paymentCallbackStrictSignature
    ) {
        this.environment = environment;
        this.adminPasswordBcrypt = adminPasswordBcrypt;
        this.paymentCallbackSecret = paymentCallbackSecret;
        this.cardEncryptionSecret = cardEncryptionSecret;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.paymentCallbackStrictSignature = paymentCallbackStrictSignature;
    }

    /** 兼容既有调用：默认按"已开启强制新版验签"构造，仅用于不关心该开关的场景。 */
    public ProductionSafetyConfigValidator(
        Environment environment,
        String adminPasswordBcrypt,
        String paymentCallbackSecret,
        String cardEncryptionSecret,
        String corsAllowedOrigins
    ) {
        this(environment, adminPasswordBcrypt, paymentCallbackSecret, cardEncryptionSecret, corsAllowedOrigins, true);
    }

    @PostConstruct
    public void validate() {
        if (!isProdProfile()) {
            if (looksLikeProductionCors(corsAllowedOrigins)) {
                throw new IllegalStateException("production HTTPS CORS origins require the prod profile");
            }
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
        if (!validCorsAllowedOrigins(corsAllowedOrigins)) {
            throw new IllegalStateException("prod profile requires explicit HTTPS CORS origins and must not use wildcards or placeholder domains");
        }
        // 批次5 / B1：过渡期开关只允许在非 prod 环境保持 false，生产必须强制 v2 验签
        if (!paymentCallbackStrictSignature) {
            throw new IllegalStateException(
                "prod profile requires xiyiyun.payment.callback-strict-signature=true so payment callbacks must carry amount/timestamp/nonce"
            );
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

    private boolean validCorsAllowedOrigins(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        var origins = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
        return !origins.isEmpty()
            && origins.stream().allMatch(origin -> origin.startsWith("https://")
                && !origin.contains("*")
                && !origin.contains("example.")
                && !origin.contains(".example")
                && !origin.contains("your-domain")
                && !origin.contains("your_domain")
                && !origin.contains("localhost")
                && !origin.contains("127.0.0.1")
                && !origin.contains("0.0.0.0"));
    }

    private boolean looksLikeProductionCors(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(origin -> origin.startsWith("https://")
                && !origin.contains("example.")
                && !origin.contains(".example")
                && !origin.contains("your-domain")
                && !origin.contains("your_domain")
                && !origin.contains("localhost")
                && !origin.contains("127.0.0.1")
                && !origin.contains("0.0.0.0"));
    }
}
