package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MemberCallbackEncryptionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void encryptsCompleteDataWithTheAppSecretAndEventIdAsAad() throws Exception {
        String body = MemberCallbackEncryption.addEncryptedData(
            "{\"eventId\":\"evt_1\",\"data\":{}}",
            "member-secret",
            "evt_1",
            "{\"rechargeAccount\":\"13800138000\",\"deliveryItems\":[\"CARD-SECRET\"]}"
        );
        JsonNode encrypted = OBJECT_MAPPER.readTree(body).path("encryptedData");

        assertThat(body).doesNotContain("13800138000", "CARD-SECRET");
        assertThat(MemberCallbackEncryption.decrypt(
            "member-secret", "evt_1",
            encrypted.path("nonce").asText(), encrypted.path("ciphertext").asText()
        )).contains("13800138000", "CARD-SECRET");
        assertThatThrownBy(() -> MemberCallbackEncryption.decrypt(
            "wrong-secret", "evt_1",
            encrypted.path("nonce").asText(), encrypted.path("ciphertext").asText()
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MemberCallbackEncryption.decrypt(
            "member-secret", "evt_tampered",
            encrypted.path("nonce").asText(), encrypted.path("ciphertext").asText()
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MemberCallbackEncryption.decrypt(
            "member-secret", "evt_1",
            "AA==", encrypted.path("ciphertext").asText()
        )).isInstanceOf(IllegalStateException.class);
    }
}
