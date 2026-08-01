package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.persistence.MemberOrderCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.MemberOrderCallbackTaskEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberOrderCallbackServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final MemberOrderCallbackTaskStore taskStore = mock(MemberOrderCallbackTaskStore.class);
    private final MemberOrderCallbackClient callbackClient = mock(MemberOrderCallbackClient.class);
    private final OutboundProtocolService protocolService = mock(OutboundProtocolService.class);
    private final MemberOrderCallbackService service = new MemberOrderCallbackService(
        taskStore, callbackClient, protocolService
    );

    @Test
    void sendsSignedJsonWithDecryptableCompleteDataAndMarksTheTaskSent() throws Exception {
        MemberOrderCallbackTaskEntity task = task();
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        when(taskStore.decryptSensitivePayload(task)).thenReturn(
            "{\"rechargeAccount\":\"13800138000\",\"deliveryItems\":[\"CARD-SECRET\"]}"
        );
        when(protocolService.callbackMemberCredential(90001L)).thenReturn(credential("https://example.com/callback"));
        when(protocolService.settings()).thenReturn(settings());

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(
            eq("https://example.com/callback"), body.capture(), headers.capture(), eq(30)
        );
        JsonNode payload = OBJECT_MAPPER.readTree(body.getValue());
        String sensitive = MemberCallbackEncryption.decrypt(
            "member-secret",
            "evt_1",
            payload.path("encryptedData").path("nonce").asText(),
            payload.path("encryptedData").path("ciphertext").asText()
        );
        assertThat(sensitive).contains("13800138000", "CARD-SECRET");
        assertThat(body.getValue()).doesNotContain("13800138000", "CARD-SECRET");
        assertThat(headers.getValue()).containsEntry("X-Xiyi-App-Key", "member_90001")
            .containsEntry("X-Xiyi-Event-Id", "evt_1")
            .containsEntry("X-Xiyi-Signature-Version", "v1");
        long timestamp = Long.parseLong(headers.getValue().get("X-Xiyi-Timestamp"));
        assertThat(headers.getValue().get("X-Xiyi-Signature"))
            .isEqualTo(MemberCallbackSignature.sign("member-secret", timestamp, body.getValue()));
        verify(taskStore).markSent(eq(1L), any());
    }

    @Test
    void clearingTheCallbackCancelsPendingDelivery() {
        MemberOrderCallbackTaskEntity task = task();
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        when(taskStore.decryptSensitivePayload(task)).thenReturn("{}");
        when(protocolService.callbackMemberCredential(90001L)).thenReturn(credential(""));

        service.dispatchDue();

        verify(taskStore).markCancelled(1L, "callbackUrl was cleared");
        verify(callbackClient, never()).post(any(), any(), anyMap(), anyInt());
    }

    @Test
    void transientFailureReturnsTheLeasedTaskToTheRetryQueue() {
        MemberOrderCallbackTaskEntity task = task();
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        when(protocolService.callbackMemberCredential(90001L)).thenReturn(credential("https://example.com/callback"));
        when(protocolService.settings()).thenReturn(settings());
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary network failure"))
            .when(callbackClient).post(any(), any(), anyMap(), anyInt());

        service.dispatchDue();

        verify(taskStore).retry(eq(1L), any(), eq("temporary network failure"));
        verify(taskStore, never()).markSent(any(), any());
    }

    @Test
    void changingTheCallbackCancelsTheOldAddressSnapshot() {
        MemberOrderCallbackTaskEntity task = task();
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        when(protocolService.callbackMemberCredential(90001L)).thenReturn(
            credential("https://new.example.com/callback")
        );

        service.dispatchDue();

        verify(taskStore).markCancelled(1L, "callbackUrl was changed");
        verify(callbackClient, never()).post(any(), any(), anyMap(), anyInt());
    }

    @Test
    void aRetriedTaskUsesTheRotatedAppSecretForEncryptionAndSignature() throws Exception {
        MemberOrderCallbackTaskEntity task = task();
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        when(taskStore.decryptSensitivePayload(task)).thenReturn("{\"deliveryItems\":[\"CARD-SECRET\"]}");
        when(protocolService.callbackMemberCredential(90001L)).thenReturn(
            credentialWithSecret("old-secret"), credentialWithSecret("new-secret")
        );
        when(protocolService.settings()).thenReturn(settings());
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary network failure"))
            .doNothing()
            .when(callbackClient).post(any(), any(), anyMap(), anyInt());

        service.dispatchDue();
        service.dispatchDue();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(callbackClient, times(2)).post(
            eq("https://example.com/callback"), bodies.capture(), headers.capture(), eq(30)
        );
        JsonNode first = OBJECT_MAPPER.readTree(bodies.getAllValues().get(0));
        JsonNode second = OBJECT_MAPPER.readTree(bodies.getAllValues().get(1));
        assertThat(MemberCallbackEncryption.decrypt(
            "old-secret", "evt_1",
            first.path("encryptedData").path("nonce").asText(),
            first.path("encryptedData").path("ciphertext").asText()
        )).contains("CARD-SECRET");
        assertThat(MemberCallbackEncryption.decrypt(
            "new-secret", "evt_1",
            second.path("encryptedData").path("nonce").asText(),
            second.path("encryptedData").path("ciphertext").asText()
        )).contains("CARD-SECRET");
        assertThatThrownBy(() -> MemberCallbackEncryption.decrypt(
            "old-secret", "evt_1",
            second.path("encryptedData").path("nonce").asText(),
            second.path("encryptedData").path("ciphertext").asText()
        )).isInstanceOf(IllegalStateException.class);
        long timestamp = Long.parseLong(headers.getAllValues().get(1).get("X-Xiyi-Timestamp"));
        assertThat(headers.getAllValues().get(1).get("X-Xiyi-Signature"))
            .isEqualTo(MemberCallbackSignature.sign("new-secret", timestamp, bodies.getAllValues().get(1)));
    }

    private MemberOrderCallbackTaskEntity task() {
        MemberOrderCallbackTaskEntity task = new MemberOrderCallbackTaskEntity();
        task.setId(1L);
        task.setEventId("evt_1");
        task.setUserId(90001L);
        task.setOrderNo("ORDER-1");
        task.setCallbackUrl("https://example.com/callback");
        task.setPayloadJson("{\"eventId\":\"evt_1\"}");
        task.setState("PENDING");
        task.setAttemptCount(0);
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }

    private MemberApiCredentialItem credential(String callbackUrl) {
        return credential(callbackUrl, "member-secret");
    }

    private MemberApiCredentialItem credentialWithSecret(String secret) {
        return credential("https://example.com/callback", secret);
    }

    private MemberApiCredentialItem credential(String callbackUrl, String secret) {
        return new MemberApiCredentialItem(
            1L, 90001L, "member_90001", secret, callbackUrl,
            "ENABLED", List.of(), 1000, OffsetDateTime.now(), null
        );
    }

    private OutboundProtocolSettings settings() {
        return new OutboundProtocolSettings(
            true, "", 60, 30, Map.of(), "ALL", List.of(), List.of(), List.of(),
            "ORIGINAL", BigDecimal.ZERO, false
        );
    }
}
