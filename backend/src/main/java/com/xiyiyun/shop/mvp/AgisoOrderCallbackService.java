package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.persistence.AgisoCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgisoOrderCallbackService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgisoOrderCallbackService.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 20;
    private static final Duration ORDER_WAIT = Duration.ofSeconds(2);
    private static final Duration ORPHAN_TTL = Duration.ofMinutes(10);
    private static final Duration INITIAL_RESPONSE_GRACE = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final AgisoCallbackTaskStore taskStore;
    private final AgisoCallbackClient callbackClient;
    private final OutboundProtocolService protocolService;
    private final String appSecret;
    private final Map<String, AgisoCallbackTaskEntity> prepared = new ConcurrentHashMap<>();

    public AgisoOrderCallbackService(
        AgisoCallbackTaskStore taskStore,
        AgisoCallbackClient callbackClient,
        OutboundProtocolService protocolService,
        @Value("${xiyiyun.agiso.app-secret:}") String appSecret
    ) {
        this.taskStore = taskStore;
        this.callbackClient = callbackClient;
        this.protocolService = protocolService;
        this.appSecret = appSecret == null ? "" : appSecret.trim();
    }

    void prepare(Long userId, String requestId, String callbackUrl, GoodsType goodsType) {
        // Register before creating the order so a process exit cannot lose the callback URL.
        callbackClient.validateCallbackUrl(callbackUrl);
        OffsetDateTime now = OffsetDateTime.now();
        AgisoCallbackTaskEntity task = taskStore.registerPending(
            userId,
            requestId,
            callbackUrl,
            goodsType.name(),
            now.plus(ORPHAN_TTL)
        );
        if (task == null) {
            throw new IllegalStateException("agiso callback task was not stored");
        }
        prepared.put(key(userId, requestId), task);
    }

    void discard(Long userId, String requestId, String reason) {
        AgisoCallbackTaskEntity task = prepared.remove(key(userId, requestId));
        if (task != null && task.getId() != null && !"SENT".equals(task.getState())) {
            taskStore.markDead(task.getId(), message(reason));
        }
    }

    void bind(Long userId, String requestId, OrderItem order) {
        prepared.remove(key(userId, requestId));
        taskStore.bindOrder(
            userId,
            requestId,
            order.orderNo(),
            OffsetDateTime.now().plus(INITIAL_RESPONSE_GRACE)
        );
    }

    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now();
        for (AgisoCallbackTaskEntity task : taskStore.findDue(now, BATCH_SIZE)) {
            try {
                process(task, now);
            } catch (RuntimeException ex) {
                LOGGER.warn("Agiso callback task {} processing failed: {}", task.getId(), ex.toString());
            }
        }
    }

    private void process(AgisoCallbackTaskEntity task, OffsetDateTime now) {
        Optional<OrderItem> order = protocolService.callbackOrder(
            task.getUserId(), task.getOrderNo(), task.getRequestId()
        );
        if (order.isEmpty()) {
            if (task.getCreatedAt() != null && task.getCreatedAt().plus(ORPHAN_TTL).isBefore(now)) {
                taskStore.markDead(task.getId(), "order was not created");
            } else {
                taskStore.deferPending(task.getId(), now.plus(ORDER_WAIT));
            }
            return;
        }
        if (!AgisoOrderPayload.callbackReady(order.get())) {
            taskStore.deferPending(task.getId(), now.plus(ORDER_WAIT));
            return;
        }
        dispatch(task, order.get());
    }

    private void dispatch(AgisoCallbackTaskEntity task, OrderItem order) {
        OffsetDateTime now = OffsetDateTime.now();
        // The database lease keeps scheduled and request-thread dispatches from sending concurrently.
        if (!taskStore.claim(task.getId(), now, now.plus(LEASE))) {
            return;
        }
        try {
            if (!StringUtils.hasText(appSecret)) {
                throw new IllegalStateException("agiso app secret is unavailable");
            }
            Map<String, Object> payload = AgisoOrderPayload.from(order, appSecret);
            payload.put("timestamp", Instant.now().getEpochSecond());
            payload.put("sign", AgisoSignatureUtil.sign(
                payload,
                appSecret + protocolService.callbackMemberSecret(task.getUserId())
            ));
            callbackClient.post(task.getCallbackUrl(), payload, protocolService.settings().timeoutSeconds());
            taskStore.markSent(task.getId(), OffsetDateTime.now());
        } catch (RuntimeException ex) {
            int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                taskStore.markDead(task.getId(), message(ex.getMessage()));
            } else {
                taskStore.retry(task.getId(), OffsetDateTime.now().plusSeconds(retryDelay(attempts)), message(ex.getMessage()));
            }
            LOGGER.warn("Agiso callback failed for order {}: {}", order.orderNo(), ex.toString());
        }
    }

    private long retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 8));
        return Math.min(300L, 1L << exponent);
    }

    private String key(Long userId, String requestId) {
        return userId + ":" + (requestId == null ? "" : requestId.trim());
    }

    private String message(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "callback failed";
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
