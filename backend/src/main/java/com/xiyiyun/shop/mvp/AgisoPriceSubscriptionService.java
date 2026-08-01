package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.AgisoPriceSubscriptionStore;
import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgisoPriceSubscriptionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgisoPriceSubscriptionService.class);
    private static final Pattern SUPPLIER_GUID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int BATCH_SIZE = 50;
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final AgisoPriceSubscriptionStore store;
    private final AgisoCallbackClient callbackClient;
    private final OutboundProtocolService protocolService;
    private final String appSecret;

    public AgisoPriceSubscriptionService(
        AgisoPriceSubscriptionStore store,
        AgisoCallbackClient callbackClient,
        OutboundProtocolService protocolService,
        @Value("${xiyiyun.agiso.app-secret:}") String appSecret
    ) {
        this.store = store;
        this.callbackClient = callbackClient;
        this.protocolService = protocolService;
        this.appSecret = appSecret == null ? "" : appSecret.trim();
    }

    void subscribe(OutboundApiPrincipal principal, String supplierAccountGuid, GoodsItem goods) {
        if (principal == null || principal.user() == null || principal.credential() == null) {
            throw new IllegalArgumentException("principal is required");
        }
        String guid = supplierGuid(supplierAccountGuid);
        if (goods == null || goods.id() == null || goods.price() == null) {
            throw new IllegalArgumentException("goods is required");
        }
        OffsetDateTime now = OffsetDateTime.now();
        store.subscribe(
            principal.user().id(),
            platformUserId(principal.credential().appKey()),
            guid,
            goods.id(),
            goods.price(),
            Instant.now().toEpochMilli(),
            now.plus(CHECK_INTERVAL)
        );
    }

    void cancel(Long userId, String supplierAccountGuid, Long productNo) {
        store.cancel(userId, supplierGuid(supplierAccountGuid), productNo);
    }

    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now();
        for (AgisoPriceSubscriptionEntity subscription : store.findDue(now, BATCH_SIZE)) {
            try {
                dispatch(subscription, now);
            } catch (RuntimeException ex) {
                LOGGER.warn("Agiso price subscription {} processing failed: {}", subscription.getId(), ex.toString());
            }
        }
    }

    private void dispatch(AgisoPriceSubscriptionEntity subscription, OffsetDateTime now) {
        String leaseToken = UUID.randomUUID().toString();
        if (!store.claim(subscription.getId(), now, now.plus(LEASE), leaseToken)) {
            return;
        }
        try {
            GoodsItem goods = protocolService.currentGoods(subscription.getUserId(), subscription.getProductNo());
            BigDecimal currentPrice = goods.price();
            if (currentPrice.compareTo(subscription.getLastNotifiedPrice()) == 0) {
                store.markUnchanged(subscription.getId(), leaseToken, OffsetDateTime.now().plus(CHECK_INTERVAL));
                return;
            }
            if (!StringUtils.hasText(appSecret)) {
                throw new IllegalStateException("agiso app secret is unavailable");
            }
            long priceVer = Math.max(
                Instant.now().toEpochMilli(),
                subscription.getLastPriceVer() == null ? 1L : subscription.getLastPriceVer() + 1L
            );
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("supplierAccountGuid", subscription.getSupplierAccountGuid());
            payload.put("productNo", String.valueOf(subscription.getProductNo()));
            payload.put("productCost", currentPrice);
            payload.put("priceVer", priceVer);
            payload.put("timestamp", Instant.now().getEpochSecond());
            payload.put("sign", AgisoSignatureUtil.sign(
                payload,
                appSecret + protocolService.callbackMemberSecret(subscription.getUserId())
            ));
            callbackClient.post(
                priceNotifyUrl(subscription.getSupplierAccountGuid()),
                payload,
                protocolService.settings().timeoutSeconds()
            );
            OffsetDateTime completedAt = OffsetDateTime.now();
            store.markNotified(
                subscription.getId(),
                leaseToken,
                currentPrice,
                priceVer,
                completedAt,
                completedAt.plus(CHECK_INTERVAL)
            );
        } catch (RuntimeException ex) {
            int attempts = subscription.getAttemptCount() == null ? 1 : subscription.getAttemptCount() + 1;
            store.retry(
                subscription.getId(),
                leaseToken,
                OffsetDateTime.now().plusSeconds(retryDelay(attempts)),
                message(ex.getMessage())
            );
            LOGGER.warn(
                "Agiso price notification failed for product {}: {}",
                subscription.getProductNo(),
                ex.toString()
            );
        }
    }

    private String priceNotifyUrl(String supplierAccountGuid) {
        return "http://cb-acpr.agiso.com/SupplierProductNotify/" + supplierGuid(supplierAccountGuid);
    }

    private String supplierGuid(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SUPPLIER_GUID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("supplierAccountGuid is invalid");
        }
        return normalized;
    }

    private String platformUserId(String appKey) {
        String normalized = appKey == null ? "" : appKey.trim();
        return normalized.startsWith("member_") ? normalized.substring("member_".length()) : normalized;
    }

    private long retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 8));
        return Math.min(300L, 1L << exponent);
    }

    private String message(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "price notification failed";
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
