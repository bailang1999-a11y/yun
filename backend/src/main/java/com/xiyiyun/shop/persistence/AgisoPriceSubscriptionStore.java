package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import com.xiyiyun.shop.persistence.mapper.AgisoPriceSubscriptionMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgisoPriceSubscriptionStore {
    private static final int MAX_BATCH_SIZE = 200;

    private final AgisoPriceSubscriptionMapper mapper;

    public AgisoPriceSubscriptionStore(AgisoPriceSubscriptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public AgisoPriceSubscriptionEntity subscribe(
        Long userId,
        String platformUserId,
        String supplierAccountGuid,
        Long productNo,
        BigDecimal currentPrice,
        long priceVer,
        OffsetDateTime nextCheckAt
    ) {
        Long effectiveUserId = requiredPositive(userId, "userId");
        String effectivePlatformUserId = requiredText(platformUserId, "platformUserId");
        String effectiveGuid = requiredText(supplierAccountGuid, "supplierAccountGuid");
        Long effectiveProductNo = requiredPositive(productNo, "productNo");
        BigDecimal effectivePrice = requiredPrice(currentPrice);
        long effectivePriceVer = requiredPriceVer(priceVer);
        OffsetDateTime effectiveNextCheckAt = requiredTime(nextCheckAt, "nextCheckAt");
        mapper.subscribe(
            effectiveUserId,
            effectivePlatformUserId,
            effectiveGuid,
            effectiveProductNo,
            effectivePrice,
            effectivePriceVer,
            effectiveNextCheckAt
        );
        return mapper.selectBySubscription(effectiveUserId, effectiveGuid, effectiveProductNo);
    }

    @Transactional
    public boolean cancel(Long userId, String supplierAccountGuid, Long productNo) {
        mapper.cancel(
            requiredPositive(userId, "userId"),
            requiredText(supplierAccountGuid, "supplierAccountGuid"),
            requiredPositive(productNo, "productNo")
        );
        return true;
    }

    @Transactional(readOnly = true)
    public List<AgisoPriceSubscriptionEntity> findDue(OffsetDateTime now, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
        return mapper.selectDue(requiredTime(now, "now"), boundedLimit);
    }

    @Transactional
    public boolean claim(Long id, OffsetDateTime now, OffsetDateTime leaseUntil, String leaseToken) {
        OffsetDateTime effectiveNow = requiredTime(now, "now");
        OffsetDateTime effectiveLeaseUntil = requiredTime(leaseUntil, "leaseUntil");
        if (!effectiveLeaseUntil.isAfter(effectiveNow)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        return mapper.claim(
            requiredPositive(id, "id"),
            effectiveNow,
            effectiveLeaseUntil,
            requiredText(leaseToken, "leaseToken")
        ) == 1;
    }

    @Transactional
    public boolean markUnchanged(Long id, String leaseToken, OffsetDateTime nextCheckAt) {
        return mapper.markUnchanged(
            requiredPositive(id, "id"),
            requiredText(leaseToken, "leaseToken"),
            requiredTime(nextCheckAt, "nextCheckAt")
        ) == 1;
    }

    @Transactional
    public boolean markNotified(
        Long id,
        String leaseToken,
        BigDecimal price,
        long priceVer,
        OffsetDateTime notifiedAt,
        OffsetDateTime nextCheckAt
    ) {
        return mapper.markNotified(
            requiredPositive(id, "id"),
            requiredText(leaseToken, "leaseToken"),
            requiredPrice(price),
            requiredPriceVer(priceVer),
            requiredTime(notifiedAt, "notifiedAt"),
            requiredTime(nextCheckAt, "nextCheckAt")
        ) == 1;
    }

    @Transactional
    public boolean retry(Long id, String leaseToken, OffsetDateTime nextCheckAt, String lastError) {
        return mapper.retry(
            requiredPositive(id, "id"),
            requiredText(leaseToken, "leaseToken"),
            requiredTime(nextCheckAt, "nextCheckAt"),
            optionalText(lastError, 1000)
        ) == 1;
    }

    private Long requiredPositive(Long value, String name) {
        if (value == null || value <= 0) {
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

    private BigDecimal requiredPrice(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("price is required");
        }
        return value;
    }

    private long requiredPriceVer(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("priceVer is required");
        }
        return value;
    }

    private OffsetDateTime requiredTime(OffsetDateTime value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String optionalText(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
