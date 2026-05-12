package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
