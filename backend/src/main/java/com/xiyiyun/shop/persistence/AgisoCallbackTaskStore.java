package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import com.xiyiyun.shop.persistence.mapper.AgisoCallbackTaskMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgisoCallbackTaskStore {
    private static final int MAX_BATCH_SIZE = 200;

    private final AgisoCallbackTaskMapper mapper;

    public AgisoCallbackTaskStore(AgisoCallbackTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public AgisoCallbackTaskEntity registerPending(
        Long userId,
        String requestId,
        String callbackUrl,
        String goodsType,
        OffsetDateTime nextAttemptAt
    ) {
        OffsetDateTime effectiveNextAttemptAt = requiredTime(nextAttemptAt, "nextAttemptAt");
        mapper.registerPending(
            requiredUserId(userId),
            requiredText(requestId, "requestId"),
            requiredText(callbackUrl, "callbackUrl"),
            requiredText(goodsType, "goodsType"),
            effectiveNextAttemptAt
        );
        return mapper.selectByUserAndRequest(userId, requestId.trim());
    }

    @Transactional
    public boolean bindOrder(Long userId, String requestId, String orderNo, OffsetDateTime nextAttemptAt) {
        return mapper.bindOrder(
            requiredUserId(userId),
            requiredText(requestId, "requestId"),
            requiredText(orderNo, "orderNo"),
            requiredTime(nextAttemptAt, "nextAttemptAt")
        ) > 0;
    }

    @Transactional(readOnly = true)
    public List<AgisoCallbackTaskEntity> findDue(OffsetDateTime now, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
        return mapper.selectDue(requiredTime(now, "now"), boundedLimit);
    }

    @Transactional
    public boolean claim(Long id, OffsetDateTime now, OffsetDateTime leaseUntil) {
        OffsetDateTime effectiveNow = requiredTime(now, "now");
        OffsetDateTime effectiveLeaseUntil = requiredTime(leaseUntil, "leaseUntil");
        if (!effectiveLeaseUntil.isAfter(effectiveNow)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        return mapper.claim(requiredId(id), effectiveNow, effectiveLeaseUntil) == 1;
    }

    @Transactional
    public boolean deferPending(Long id, OffsetDateTime nextAttemptAt) {
        return mapper.deferPending(requiredId(id), requiredTime(nextAttemptAt, "nextAttemptAt")) == 1;
    }

    @Transactional
    public boolean retry(Long id, OffsetDateTime nextAttemptAt, String lastError) {
        return mapper.retry(
            requiredId(id),
            requiredTime(nextAttemptAt, "nextAttemptAt"),
            optionalText(lastError, 1000)
        ) == 1;
    }

    @Transactional
    public boolean markSent(Long id, OffsetDateTime sentAt) {
        return mapper.markSent(requiredId(id), requiredTime(sentAt, "sentAt")) == 1;
    }

    @Transactional
    public boolean markDead(Long id, String lastError) {
        return mapper.markDead(requiredId(id), optionalText(lastError, 1000)) == 1;
    }

    @Transactional
    public boolean markDeadIfUnbound(Long id, String lastError) {
        return mapper.markDeadIfUnbound(requiredId(id), optionalText(lastError, 1000)) == 1;
    }

    @Transactional
    public int recoverLegacyRejectedTasks(OffsetDateTime nextAttemptAt) {
        return mapper.recoverLegacyRejectedTasks(requiredTime(nextAttemptAt, "nextAttemptAt"));
    }

    private Long requiredUserId(Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
        return value;
    }

    private Long requiredId(Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("id is required");
        }
        return value;
    }

    private OffsetDateTime requiredTime(OffsetDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String requiredText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private String optionalText(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
