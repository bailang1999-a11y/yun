package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OutboundProtocolService {
    static final Set<String> PROTOCOL_IDS = Set.of(
        "KASUSHOU", "KAKAYUN", "FULU", "FENGZHUSHOU", "CHENGQUAN", "FANCHEN", "JINGZHAO"
    );
    private static final Set<String> DELIVERY_TYPES = Set.of("CARD", "DIRECT");
    private static final Set<String> EXPOSURE_MODES = Set.of("ALL", "CATEGORY", "SELECTED");
    private static final String DEFAULT_PUBLIC_API_BASE_URL = "https://api.xiyi.co";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InMemoryShopRepository repository;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private volatile OutboundProtocolSettings cachedSettings = defaults();
    private volatile boolean settingsLoaded;

    public OutboundProtocolService(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    public synchronized OutboundProtocolSettings settings() {
        if (!settingsLoaded) {
            String raw = repository.outboundProtocolSettingsJson();
            if (StringUtils.hasText(raw)) {
                try {
                    cachedSettings = normalize(OBJECT_MAPPER.readValue(raw, OutboundProtocolSettingsRequest.class));
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("对外供货协议配置损坏，请重新保存");
                }
            }
            settingsLoaded = true;
        }
        return cachedSettings;
    }

    public synchronized OutboundProtocolSettings save(OutboundProtocolSettingsRequest request) {
        OutboundProtocolSettings normalized = normalize(request);
        try {
            repository.saveOutboundProtocolSettingsJson(OBJECT_MAPPER.writeValueAsString(normalized));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("对外供货协议配置保存失败");
        }
        cachedSettings = normalized;
        settingsLoaded = true;
        return normalized;
    }

    OutboundApiPrincipal authorize(String protocol, String appKey, String path, String clientIp) {
        String normalizedProtocol = normalizeProtocol(protocol);
        OutboundProtocolSettings current = settings();
        if (!current.enabled() || !Boolean.TRUE.equals(current.protocols().get(normalizedProtocol))) {
            throw new IllegalStateException("protocol disabled");
        }
        return authorizeCredential(appKey, path, clientIp);
    }

    OutboundApiPrincipal authorizeCredential(String appKey, String path, String clientIp) {
        OutboundProtocolSettings current = settings();
        if (!current.enabled()) {
            throw new IllegalStateException("outbound supply disabled");
        }
        OutboundApiPrincipal principal = repository.prepareOutboundCredential(appKey, path, clientIp);
        try {
            enforceMinuteLimit(principal.credential().appKey(), current.requestLimitPerMinute());
        } catch (IllegalStateException ex) {
            repository.rejectOutboundCredential(principal, appKey, path, ex.getMessage());
            throw ex;
        }
        return principal;
    }

    void accept(OutboundApiPrincipal principal, String path) {
        repository.completeOutboundCredential(principal, path);
    }

    void reject(OutboundApiPrincipal principal, String appKey, String path, String message) {
        repository.rejectOutboundCredential(principal, appKey, path, message);
    }

    List<GoodsItem> goods(OutboundApiPrincipal principal, Long categoryId, String search) {
        OutboundProtocolSettings current = settings();
        return repository.listGoods(categoryId, search, "api", principal.user().groupId(), false).stream()
            .filter(item -> allowedByExposure(item, current))
            .filter(item -> item.type() != null && current.deliveryTypes().contains(item.type().name()))
            .toList();
    }

    GoodsItem goods(OutboundApiPrincipal principal, Long goodsId) {
        if (goodsId == null) {
            throw new IllegalArgumentException("productNo is required");
        }
        GoodsItem item = repository.findGoods(goodsId).orElseThrow(() -> new IllegalArgumentException("goods not found"));
        return goods(principal, item.categoryId(), "").stream()
            .filter(candidate -> Objects.equals(candidate.id(), goodsId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("goods unavailable"));
    }

    GoodsItem currentGoods(Long userId, Long goodsId) {
        if (goodsId == null) {
            throw new IllegalArgumentException("productNo is required");
        }
        UserItem user = repository.findOutboundUser(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found"));
        GoodsItem item = repository.findGoods(goodsId, user.groupId(), false, "api")
            .orElseThrow(() -> new IllegalArgumentException("goods unavailable"));
        OutboundProtocolSettings current = settings();
        if (!current.enabled()
            || !allowedByExposure(item, current)
            || item.type() == null
            || !current.deliveryTypes().contains(item.type().name())) {
            throw new IllegalArgumentException("goods unavailable");
        }
        return item;
    }

    List<RechargeFieldItem> rechargeFields(GoodsItem item) {
        Set<String> codes = item == null || item.accountTypes() == null
            ? Set.of()
            : Set.copyOf(item.accountTypes());
        return repository.listRechargeFields(true).stream()
            .filter(field -> codes.contains(field.code()))
            .toList();
    }

    OrderItem createOrder(
        OutboundApiPrincipal principal,
        Long goodsId,
        Integer quantity,
        String rechargeAccount,
        String buyerRemark,
        String requestId,
        Map<String, String> rechargeFields,
        String clientIp
    ) {
        return createOrder(
            principal, goodsId, quantity, rechargeAccount, buyerRemark,
            requestId, rechargeFields, clientIp, null
        );
    }

    OrderItem createOrder(
        OutboundApiPrincipal principal,
        Long goodsId,
        Integer quantity,
        String rechargeAccount,
        String buyerRemark,
        String requestId,
        Map<String, String> rechargeFields,
        String clientIp,
        BigDecimal externalMaxAmount
    ) {
        GoodsItem goods = repository.findGoods(goodsId).orElseThrow(() -> new IllegalArgumentException("goods not found"));
        if (!goods(principal, goods.categoryId(), "").stream().anyMatch(item -> Objects.equals(item.id(), goodsId))) {
            throw new IllegalArgumentException("goods unavailable");
        }
        return repository.createMemberOrder(new CreateOrderRequest(
            goodsId,
            quantity == null ? 1 : quantity,
            rechargeAccount,
            buyerRemark,
            requestId,
            "api",
            rechargeFields == null ? Map.of() : rechargeFields
        ), principal.user().id(), clientIp, externalMaxAmount);
    }

    OrderItem findOrder(OutboundApiPrincipal principal, String orderNo, String requestId) {
        if (StringUtils.hasText(orderNo)) {
            return repository.findOrderForUser(orderNo.trim(), principal.user().id())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        }
        if (StringUtils.hasText(requestId)) {
            return repository.findOrderByRequestId(principal.user().id(), requestId.trim())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        }
        throw new IllegalArgumentException("order number is required");
    }

    OrderItem cancelOrder(OutboundApiPrincipal principal, String requestId) {
        OrderItem order = findOrder(principal, "", requestId);
        return repository.cancelOrder(order.orderNo(), principal.user().id());
    }

    Optional<OrderItem> callbackOrder(Long userId, String orderNo, String requestId) {
        if (userId == null) {
            return Optional.empty();
        }
        if (StringUtils.hasText(orderNo)) {
            return repository.findOrderForUser(orderNo.trim(), userId);
        }
        if (StringUtils.hasText(requestId)) {
            return repository.findOrderByRequestId(userId, requestId.trim());
        }
        return Optional.empty();
    }

    String callbackMemberSecret(Long userId) {
        MemberApiCredentialItem credential = repository.memberCredentialForUser(userId);
        if (!"ENABLED".equals(credential.status()) || !StringUtils.hasText(credential.appSecret())) {
            throw new IllegalStateException("member API credential is unavailable");
        }
        return credential.appSecret();
    }

    MemberApiCredentialItem callbackMemberCredential(Long userId) {
        return repository.memberCredentialForUser(userId);
    }

    private boolean allowedByExposure(GoodsItem item, OutboundProtocolSettings current) {
        return switch (current.exposureMode()) {
            case "CATEGORY" -> current.categoryIds().contains(item.categoryId());
            case "SELECTED" -> current.goodsIds().contains(item.id());
            default -> true;
        };
    }

    private void enforceMinuteLimit(String appKey, int limit) {
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = rateWindows.compute(appKey, (key, current) -> {
            if (current == null || current.minute() != minute) {
                return new RateWindow(minute, new AtomicInteger(1));
            }
            current.count().incrementAndGet();
            return current;
        });
        if (window.count().get() > limit) {
            throw new IllegalStateException("rate limit exceeded");
        }
    }

    private OutboundProtocolSettings normalize(OutboundProtocolSettingsRequest request) {
        OutboundProtocolSettings current = settingsLoaded ? cachedSettings : defaults();
        Map<String, Boolean> protocols = new LinkedHashMap<>(current.protocols());
        if (request != null && request.protocols() != null) {
            request.protocols().forEach((key, value) -> {
                String normalized = normalizeProtocol(key);
                protocols.put(normalized, Boolean.TRUE.equals(value));
            });
        }
        String exposureMode = enumValue(request == null ? null : request.exposureMode(), EXPOSURE_MODES, current.exposureMode());
        List<String> deliveryTypes = request == null || request.deliveryTypes() == null
            ? current.deliveryTypes()
            : request.deliveryTypes().stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(DELIVERY_TYPES::contains)
                .distinct()
                .toList();
        if (deliveryTypes.isEmpty()) {
            throw new IllegalArgumentException("至少开放一种交付类型");
        }
        return new OutboundProtocolSettings(
            request == null ? current.enabled() : Boolean.TRUE.equals(request.enabled()),
            nonBlankText(request == null ? null : request.baseUrl(), current.baseUrl()),
            clamp(request == null ? null : request.requestLimitPerMinute(), current.requestLimitPerMinute(), 1, 10_000),
            clamp(request == null ? null : request.timeoutSeconds(), current.timeoutSeconds(), 5, 120),
            Map.copyOf(protocols),
            exposureMode,
            longList(request == null ? null : request.categoryIds()),
            longList(request == null ? null : request.goodsIds()),
            List.copyOf(deliveryTypes),
            "MEMBER_GROUP",
            BigDecimal.ZERO,
            false
        );
    }

    private static OutboundProtocolSettings defaults() {
        Map<String, Boolean> protocols = new LinkedHashMap<>();
        PROTOCOL_IDS.stream().sorted().forEach(id -> protocols.put(id, false));
        return new OutboundProtocolSettings(
            false, DEFAULT_PUBLIC_API_BASE_URL, 120, 30, Map.copyOf(protocols), "ALL", List.of(), List.of(),
            List.of(GoodsType.CARD.name(), GoodsType.DIRECT.name()), "MEMBER_GROUP", BigDecimal.ZERO, false
        );
    }

    private static String normalizeProtocol(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PROTOCOL_IDS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported protocol");
        }
        return normalized;
    }

    private static String enumValue(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String text(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private static String nonBlankText(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static List<Long> longList(List<Long> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).filter(value -> value > 0).toList()));
    }

    private record RateWindow(long minute, AtomicInteger count) {
    }
}
