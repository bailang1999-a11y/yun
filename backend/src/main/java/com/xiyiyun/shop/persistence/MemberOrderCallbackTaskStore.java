package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.MemberOrderCallbackPayload;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.entity.MemberOrderCallbackTaskEntity;
import com.xiyiyun.shop.persistence.mapper.MemberOrderCallbackTaskMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberOrderCallbackTaskStore {
    private static final int MAX_BATCH_SIZE = 200;

    private final MemberOrderCallbackTaskMapper mapper;
    private final CardCipherService cardCipherService;

    public MemberOrderCallbackTaskStore(MemberOrderCallbackTaskMapper mapper, CardCipherService cardCipherService) {
        this.mapper = mapper;
        this.cardCipherService = cardCipherService;
    }

    @Transactional
    public void registerTerminalOrder(OrderItem order) {
        OffsetDateTime now = OffsetDateTime.now();
        MemberOrderCallbackPayload.from(order, now.toInstant()).ifPresent(event -> {
            CardCipherService.EncryptedCard sensitive = cardCipherService.encrypt(event.sensitiveJson());
            mapper.registerPending(
                event.eventId(), order.userId(), order.requestId().trim(), order.orderNo(), event.eventType(),
                event.orderStatus(), event.payloadJson(), sensitive.ciphertext(), sensitive.nonce(),
                sensitive.keyVersion(), now
            );
        });
    }

    @Transactional(readOnly = true)
    public MemberOrderCallbackTaskEntity findByEventId(String eventId) {
        return mapper.selectByEventId(requiredText(eventId, "eventId"));
    }

    @Transactional(readOnly = true)
    public List<MemberOrderCallbackTaskEntity> findDue(OffsetDateTime now, int limit) {
        return mapper.selectDue(requiredTime(now, "now"), Math.max(1, Math.min(limit, MAX_BATCH_SIZE)));
    }

    @Transactional
    public boolean claim(Long id, OffsetDateTime now, OffsetDateTime leaseUntil) {
        OffsetDateTime effectiveNow = requiredTime(now, "now");
        OffsetDateTime effectiveLease = requiredTime(leaseUntil, "leaseUntil");
        if (!effectiveLease.isAfter(effectiveNow)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        return mapper.claim(requiredId(id), effectiveNow, effectiveLease) == 1;
    }

    @Transactional
    public boolean retry(Long id, OffsetDateTime nextAttemptAt, String error) {
        return mapper.retry(requiredId(id), requiredTime(nextAttemptAt, "nextAttemptAt"), text(error, 1000)) == 1;
    }

    @Transactional
    public boolean markSent(Long id, OffsetDateTime sentAt) {
        return mapper.markSent(requiredId(id), requiredTime(sentAt, "sentAt")) == 1;
    }

    @Transactional
    public boolean markDead(Long id, String error) {
        return mapper.markDead(requiredId(id), text(error, 1000)) == 1;
    }

    @Transactional
    public boolean markCancelled(Long id, String reason) {
        return mapper.markCancelled(requiredId(id), text(reason, 1000)) == 1;
    }

    @Transactional
    public int cancelPendingForUserExceptUrl(Long userId, String callbackUrl, String reason) {
        String normalized = callbackUrl == null ? "" : callbackUrl.trim();
        return mapper.cancelPendingForUserExceptUrl(
            requiredId(userId), normalized, text(reason, 1000)
        );
    }

    public String decryptSensitivePayload(MemberOrderCallbackTaskEntity task) {
        if (task == null || task.getSensitiveCiphertext() == null || task.getSensitiveNonce() == null) {
            throw new IllegalStateException("member callback sensitive payload is unavailable");
        }
        return cardCipherService.decrypt(
            task.getSensitiveCiphertext(), task.getSensitiveNonce(), task.getSensitiveKeyVersion()
        );
    }

    @Transactional
    public int deleteByOrderNo(String orderNo) {
        return mapper.hardDeleteByOrderNo(requiredText(orderNo, "orderNo"));
    }

    private Long requiredId(Long value) {
        if (value == null || value <= 0) throw new IllegalArgumentException("id is required");
        return value;
    }

    private OffsetDateTime requiredTime(OffsetDateTime value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String requiredText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private String text(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
