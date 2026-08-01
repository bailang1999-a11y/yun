package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MemberApiAuthenticationTest {
    @Test
    void replayingMemberApiNonceFailsAfterFirstSuccessfulAuthentication() {
        InMemoryShopRepository repository = new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
        String path = "/api/member/balance";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-replay-unit-test";
        String signature = hmacSha256("demo_app_secret", timestamp + "\n" + nonce + "\n" + path);

        UserItem user = repository.authenticateMemberApi("demo_app_key", timestamp, nonce, signature, path, "127.0.0.1");

        assertThat(user.id()).isEqualTo(90002L);
        assertThatThrownBy(() -> repository.authenticateMemberApi("demo_app_key", timestamp, nonce, signature, path, "127.0.0.1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("nonce replay");
        assertThat(repository.listOpenApiLogs()).anySatisfy(log -> {
            assertThat(log.appKey()).isEqualTo("demo_app_key");
            assertThat(log.status()).isEqualTo("FAILED");
            assertThat(log.message()).isEqualTo("nonce replay");
        });
    }

    @Test
    void memberApiDailyLimitRejectsRequestsAfterLimit() {
        InMemoryShopRepository repository = new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
        repository.saveMemberCredential(90002L, new MemberApiCredentialRequest(
            true, "demo_app_key", "demo_app_secret", false, List.of(), 1
        ));
        String path = "/api/member/balance";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String firstNonce = "daily-limit-first";
        String secondNonce = "daily-limit-second";

        repository.authenticateMemberApi(
            "demo_app_key",
            timestamp,
            firstNonce,
            hmacSha256("demo_app_secret", timestamp + "\n" + firstNonce + "\n" + path),
            path,
            "127.0.0.1"
        );

        assertThatThrownBy(() -> repository.authenticateMemberApi(
            "demo_app_key",
            timestamp,
            secondNonce,
            hmacSha256("demo_app_secret", timestamp + "\n" + secondNonce + "\n" + path),
            path,
            "127.0.0.1"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("daily limit exceeded");
    }

    @Test
    void memberApiWriteSignatureBindsContentHash() {
        InMemoryShopRepository repository = new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
        String path = "/api/member/orders";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String contentHash = "ab".repeat(32);
        String nonce = "body-signature-success";
        String signedPayload = timestamp + "\n" + nonce + "\n" + path + "\n" + contentHash;

        UserItem user = repository.authenticateMemberApi(
            "demo_app_key", timestamp, nonce, hmacSha256("demo_app_secret", signedPayload), path, "127.0.0.1", contentHash
        );

        assertThat(user.id()).isEqualTo(90002L);
        String unsignedBodyNonce = "body-signature-failure";
        assertThatThrownBy(() -> repository.authenticateMemberApi(
            "demo_app_key",
            timestamp,
            unsignedBodyNonce,
            hmacSha256("demo_app_secret", timestamp + "\n" + unsignedBodyNonce + "\n" + path),
            path,
            "127.0.0.1",
            contentHash
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid signature");
    }

    private static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
