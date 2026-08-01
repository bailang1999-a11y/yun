package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.MemberOrderCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.MemberOrderCallbackTaskEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemberOrderCallbackService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemberOrderCallbackService.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 20;
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final MemberOrderCallbackTaskStore taskStore;
    private final MemberOrderCallbackClient callbackClient;
    private final OutboundProtocolService protocolService;

    public MemberOrderCallbackService(
        MemberOrderCallbackTaskStore taskStore,
        MemberOrderCallbackClient callbackClient,
        OutboundProtocolService protocolService
    ) {
        this.taskStore = taskStore;
        this.callbackClient = callbackClient;
        this.protocolService = protocolService;
    }

    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now();
        for (MemberOrderCallbackTaskEntity task : taskStore.findDue(now, BATCH_SIZE)) {
            try {
                dispatch(task, now);
            } catch (RuntimeException ex) {
                LOGGER.warn("Member callback task {} processing failed: {}", task.getId(), ex.toString());
            }
        }
    }

    private void dispatch(MemberOrderCallbackTaskEntity task, OffsetDateTime now) {
        if (!taskStore.claim(task.getId(), now, now.plus(LEASE))) {
            return;
        }
        try {
            MemberApiCredentialItem credential = protocolService.callbackMemberCredential(task.getUserId());
            if (!StringUtils.hasText(credential.callbackUrl())) {
                taskStore.markCancelled(task.getId(), "callbackUrl was cleared");
                return;
            }
            String taskCallbackUrl = task.getCallbackUrl() == null ? "" : task.getCallbackUrl().trim();
            if (!taskCallbackUrl.equals(credential.callbackUrl().trim())) {
                taskStore.markCancelled(task.getId(), "callbackUrl was changed");
                return;
            }
            long requestTimestamp = Instant.now().getEpochSecond();
            String body = MemberCallbackEncryption.addEncryptedData(
                task.getPayloadJson() == null ? "{}" : task.getPayloadJson(),
                credential.appSecret(),
                task.getEventId(),
                taskStore.decryptSensitivePayload(task)
            );
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Xiyi-App-Key", credential.appKey());
            headers.put("X-Xiyi-Event-Id", task.getEventId());
            headers.put("X-Xiyi-Timestamp", String.valueOf(requestTimestamp));
            headers.put("X-Xiyi-Signature-Version", "v1");
            headers.put("X-Xiyi-Signature", MemberCallbackSignature.sign(
                credential.appSecret(), requestTimestamp, body
            ));
            callbackClient.post(
                taskCallbackUrl, body, headers, protocolService.settings().timeoutSeconds()
            );
            taskStore.markSent(task.getId(), OffsetDateTime.now());
        } catch (IllegalArgumentException ex) {
            taskStore.markDead(task.getId(), message(ex.getMessage()));
        } catch (RuntimeException ex) {
            int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                taskStore.markDead(task.getId(), message(ex.getMessage()));
            } else {
                taskStore.retry(
                    task.getId(), OffsetDateTime.now().plusSeconds(retryDelay(attempts)), message(ex.getMessage())
                );
            }
            LOGGER.warn("Member order callback failed for {}: {}", task.getOrderNo(), ex.toString());
        }
    }

    private long retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 8));
        return Math.min(300L, 1L << exponent);
    }

    private String message(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "callback failed";
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
