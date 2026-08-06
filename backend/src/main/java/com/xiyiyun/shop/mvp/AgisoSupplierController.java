package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.persistence.AgisoRejectedOrderStore;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgisoSupplierController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgisoSupplierController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 600;
    private static final int API_TYPE_SYNC = 1;
    private static final int API_TYPE_ASYNC = 2;
    private final OutboundProtocolService service;
    private final AgisoCallbackClient callbackClient;
    private final AgisoOrderCallbackService callbackService;
    private final AgisoPriceSubscriptionService priceSubscriptionService;
    private final boolean enabled;
    private final String appId;
    private final String appSecret;
    private final long syncOrderTimeoutMillis;
    private final long syncOrderPollIntervalMillis;
    @Autowired(required = false)
    private AgisoRejectedOrderStore rejectedOrderStore;

    @Autowired
    public AgisoSupplierController(
        OutboundProtocolService service,
        AgisoCallbackClient callbackClient,
        AgisoOrderCallbackService callbackService,
        AgisoPriceSubscriptionService priceSubscriptionService,
        @Value("${xiyiyun.agiso.enabled:false}") boolean enabled,
        @Value("${xiyiyun.agiso.app-id:}") String appId,
        @Value("${xiyiyun.agiso.app-secret:}") String appSecret,
        @Value("${xiyiyun.agiso.sync-order-timeout-ms:25000}") long syncOrderTimeoutMillis,
        @Value("${xiyiyun.agiso.sync-order-poll-interval-ms:500}") long syncOrderPollIntervalMillis
    ) {
        this.service = service;
        this.callbackClient = callbackClient;
        this.callbackService = callbackService;
        this.priceSubscriptionService = priceSubscriptionService;
        this.enabled = enabled;
        this.appId = clean(appId);
        this.appSecret = clean(appSecret);
        this.syncOrderTimeoutMillis = Math.max(1L, syncOrderTimeoutMillis);
        this.syncOrderPollIntervalMillis = Math.max(1L, syncOrderPollIntervalMillis);
    }

    @PostMapping("/agisoAcprSupplierApi/app/getAppId")
    public Map<String, Object> appId() {
        if (!configured()) {
            return error(503, "agiso supplier is not configured");
        }
        return success(Map.of("appId", numericAppId()));
    }

    @PostMapping("/agisoAcprSupplierApi/product/getList")
    public Map<String, Object> products(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> {
            int requestedType = intValue(payload, "productType", 0);
            List<GoodsItem> all = service.goods(principal, null, text(payload, "keyword")).stream()
                .filter(item -> requestedType == 0 || productType(item) == requestedType)
                .toList();
            int pageIndex = Math.max(1, intValue(payload, "pageIndex", 1));
            int pageSize = Math.max(1, Math.min(200, intValue(payload, "pageSize", 20)));
            int from = Math.min(all.size(), (pageIndex - 1) * pageSize);
            int to = Math.min(all.size(), from + pageSize);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", all.subList(from, to).stream().map(this::productSummary).toList());
            data.put("hasNextPage", to < all.size());
            return success(data);
        });
    }

    @PostMapping("/agisoAcprSupplierApi/product/getTemplate")
    public Map<String, Object> productTemplate(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> success(productTemplate(
            service.goods(principal, longValue(payload, "productNo"))
        )));
    }

    @PostMapping("/agisoAcprSupplierApi/product/subscribePriceNotify")
    public Map<String, Object> subscribePriceNotify(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> {
            String supplierAccountGuid = requiredText(payload, "supplierAccountGuid");
            GoodsItem goods = service.goods(principal, longValue(payload, "productNo"));
            priceSubscriptionService.subscribe(principal, supplierAccountGuid, goods);
            return success(null);
        });
    }

    @PostMapping("/agisoAcprSupplierApi/product/cancelSubscribePriceNotify")
    public Map<String, Object> cancelSubscribePriceNotify(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> {
            String supplierAccountGuid = requiredText(payload, "supplierAccountGuid");
            Long productNo = longValue(payload, "productNo");
            priceSubscriptionService.cancel(principal.user().id(), supplierAccountGuid, productNo);
            return success(null);
        });
    }

    @PostMapping("/agisoAcprSupplierApi/order/createRecharge")
    public Map<String, Object> createRecharge(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        return createOrder(safe(body), request, GoodsType.DIRECT);
    }

    @PostMapping("/agisoAcprSupplierApi/order/createPurchase")
    public Map<String, Object> createPurchase(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        return createOrder(safe(body), request, GoodsType.CARD);
    }

    @PostMapping("/agisoAcprSupplierApi/order/get")
    public Map<String, Object> order(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> success(AgisoOrderPayload.from(service.findOrder(
            principal, "", text(payload, "orderNo")
        ), appSecret)));
    }

    @PostMapping({
        "/agisoAcprSupplierApi/order/cancel",
        "/agisoAcprSupplierApi/order/cancelOrder"
    })
    public Map<String, Object> cancel(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return execute(payload, request, principal -> {
            String orderNo = text(payload, "orderNo");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderNo", orderNo);
            try {
                OrderItem cancelled = service.cancelOrder(principal, orderNo);
                result.put("cancelStatus", 20);
                result.put("refundAmount", money(cancelled.payAmount()));
                result.put("refuseReason", "");
                result.put("refuseProof", "");
            } catch (IllegalStateException ex) {
                result.put("cancelStatus", 30);
                result.put("refundAmount", BigDecimal.ZERO);
                result.put("refuseReason", ex.getMessage());
                result.put("refuseProof", "");
            }
            notifyCancel(payload, result, principal);
            return success(result);
        });
    }

    private Map<String, Object> createOrder(Map<String, Object> payload, HttpServletRequest request, GoodsType expectedType) {
        return execute(payload, request, principal -> createVerifiedOrder(payload, request, expectedType, principal));
    }

    private Map<String, Object> createVerifiedOrder(
        Map<String, Object> payload,
        HttpServletRequest request,
        GoodsType expectedType,
        OutboundApiPrincipal principal
    ) {
        GoodsItem goods = null;
        BigDecimal expectedCost = null;
        String rechargeAccount = "";
        Map<String, String> rechargeFields = Map.of();
        OrderItem createdOrder = null;
        try {
            Map<String, Object> attach = parseJsonMap(text(payload, "attach"));
            rechargeAccount = firstValue(attach, "account", "rechargeAccount", "recharge_account");
            if (!StringUtils.hasText(rechargeAccount)) {
                rechargeAccount = firstValue(payload, "account", "rechargeAccount", "recharge_account");
            }
            goods = service.goods(principal, longValue(payload, "productNo"));
            if (goods.type() != expectedType) {
                throw new IllegalArgumentException(expectedType == GoodsType.CARD
                    ? "product is not a card product"
                    : "product is not a recharge product");
            }
            int quantity = intValue(payload, "buyNum", 1);
            rechargeFields = rechargeFields(goods, attach);
            expectedCost = money(goods.price()).multiply(BigDecimal.valueOf(quantity));
            BigDecimal externalMaxAmount = validateMaxAmount(payload, expectedCost);
            String externalOrderNo = requiredText(payload, "orderNo");
            String callbackUrl = text(payload, "callbackUrl");
            boolean async = apiType(goods) == API_TYPE_ASYNC;
            if (async && !StringUtils.hasText(callbackUrl)) {
                throw new AgisoAsyncCallbackRequiredException();
            }
            if (async) {
                callbackService.prepare(principal.user().id(), externalOrderNo, callbackUrl, expectedType);
            }
            try {
                createdOrder = service.createOrder(
                    principal,
                    goods.id(),
                    quantity,
                    rechargeAccount,
                    "阿奇索标准货源订单",
                    externalOrderNo,
                    rechargeFields,
                    clientIp(request),
                    externalMaxAmount
                );
            } catch (RuntimeException ex) {
                if (async) {
                    callbackService.discard(principal.user().id(), externalOrderNo, ex.getMessage());
                }
                throw ex;
            }
            OrderItem order = createdOrder;
            if (!async) {
                order = awaitSynchronousResult(principal, order);
            }
            Map<String, Object> data = AgisoOrderPayload.from(order, appSecret);
            if (async) {
                data.put("orderStatus", 10);
                callbackService.bind(principal.user().id(), externalOrderNo, order);
            }
            if (rejectedOrderStore != null) {
                resolveRejectedOrder(principal.user().id(), externalOrderNo, order.orderNo());
            }
            return success(data);
        } catch (RuntimeException ex) {
            if (rejectedOrderStore != null && createdOrder == null && isBusinessRejection(ex)) {
                Map<String, Object> response = errorCode(ex);
                recordRejectedOrder(
                    principal, payload, goods, expectedType, expectedCost, rechargeAccount, rechargeFields,
                    String.valueOf(response.get("code")), rejectReason(ex)
                );
            }
            throw ex;
        }
    }

    private static String buyerAccount(UserItem user) {
        if (user == null) return "";
        if (StringUtils.hasText(user.username())) return user.username();
        if (StringUtils.hasText(user.mobile())) return user.mobile();
        return clean(user.nickname());
    }

    private static String rejectReason(RuntimeException ex) {
        if (ex instanceof AgisoMaxAmountException) return "实际支付价格低于系统要求价格";
        if (ex instanceof AgisoAsyncCallbackRequiredException) return "异步商品未提供回调地址";
        String message = clean(ex.getMessage());
        if ("goods not found".equals(message) || "goods unavailable".equals(message)) return "商品不存在或不可售";
        if (message.contains("not a card product")) return "商品不是卡密商品";
        if (message.contains("not a recharge product")) return "商品不是直充商品";
        if (message.contains("stock")) return "商品库存不足";
        return message.isEmpty() ? "下单请求未通过业务校验" : message;
    }

    private static boolean isBusinessRejection(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException) return true;
        String message = clean(ex.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("stock")
            || message.contains("库存")
            || message.contains("balance")
            || message.contains("余额")
            || message.contains("unavailable")
            || message.contains("disabled")
            || message.contains("forbidden")
            || message.contains("不允许")
            || message.contains("不可售");
    }

    private void recordRejectedOrder(
        OutboundApiPrincipal principal,
        Map<String, Object> payload,
        GoodsItem goods,
        GoodsType requestedType,
        BigDecimal expectedCost,
        String rechargeAccount,
        Map<String, String> rechargeFields,
        String rejectCode,
        String rejectReason
    ) {
        try {
            rejectedOrderStore.record(
                principal.user().id(), buyerAccount(principal.user()), payload, goods, requestedType, expectedCost,
                rechargeAccount, rechargeFields, rejectCode, rejectReason
            );
        } catch (RuntimeException storeFailure) {
            LOGGER.error(
                "Failed to persist authenticated Agiso rejection userId={} orderNo={}",
                principal.user().id(), text(payload, "orderNo"), storeFailure
            );
        }
    }

    private void resolveRejectedOrder(Long userId, String externalOrderNo, String orderNo) {
        try {
            rejectedOrderStore.resolve(userId, externalOrderNo, orderNo);
        } catch (RuntimeException storeFailure) {
            LOGGER.error(
                "Failed to resolve prior Agiso rejection userId={} orderNo={}",
                userId, externalOrderNo, storeFailure
            );
        }
    }

    private OrderItem awaitSynchronousResult(OutboundApiPrincipal principal, OrderItem created) {
        OrderItem current = created;
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(syncOrderTimeoutMillis);
        long startedAt = System.nanoTime();
        while (!AgisoOrderPayload.callbackReady(current)) {
            if (System.nanoTime() - startedAt >= timeoutNanos) {
                throw new AgisoSyncOrderTimeoutException();
            }
            current = service.findOrder(principal, current.orderNo(), "");
            if (AgisoOrderPayload.callbackReady(current)) {
                return current;
            }
            long remainingNanos = timeoutNanos - (System.nanoTime() - startedAt);
            if (remainingNanos <= 0) {
                throw new AgisoSyncOrderTimeoutException();
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                    TimeUnit.MILLISECONDS.toNanos(syncOrderPollIntervalMillis), remainingNanos
                ));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AgisoSyncOrderTimeoutException();
            }
        }
        return current;
    }

    private Map<String, Object> execute(
        Map<String, Object> payload,
        HttpServletRequest request,
        Function<OutboundApiPrincipal, Map<String, Object>> action
    ) {
        if (!configured()) {
            return error(503, "agiso supplier is not configured");
        }
        String userId = text(payload, "userId");
        String appKey = userId.startsWith("member_") ? userId : "member_" + userId;
        OutboundApiPrincipal principal = null;
        String path = request.getRequestURI();
        try {
            if (!StringUtils.hasText(userId)) {
                throw new IllegalArgumentException("userId is required");
            }
            principal = service.authorizeCredential(appKey, path, clientIp(request));
            if (!freshTimestamp(text(payload, "timestamp"))) {
                throw new IllegalArgumentException("timestamp expired");
            }
            String expected = AgisoSignatureUtil.sign(payload, merchantSecret(principal));
            if (!secureEquals(expected, text(payload, "sign"))) {
                throw new IllegalArgumentException("invalid signature");
            }
            Map<String, Object> response = action.apply(principal);
            service.accept(principal, path);
            return response;
        } catch (RuntimeException ex) {
            if (principal != null) {
                service.reject(principal, appKey, path, failureAuditMessage(payload, ex));
            }
            return errorCode(ex);
        }
    }

    private Map<String, Object> productSummary(GoodsItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productNo", String.valueOf(item.id()));
        result.put("productTitle", clean(item.goodsName()));
        result.put("productType", productType(item));
        result.put("productCost", money(item.price()));
        result.put("productDescribe", clean(item.description()));
        result.put("productImageUrl", clean(item.coverUrl()));
        result.put("disableSaleChannels", disableSaleChannels(item.forbiddenPlatforms()));
        result.put("minSalePrice", money(item.price()));
        result.put("isAllowPurchase", "ON_SALE".equals(item.status()) && value(item.stock()) > 0);
        return result;
    }

    private Map<String, Object> productTemplate(GoodsItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productNo", String.valueOf(item.id()));
        result.put("apiType", apiType(item));
        result.put("productTitle", clean(item.goodsName()));
        result.put("productType", productType(item));
        result.put("productCost", money(item.price()));
        result.put("productStock", value(item.stock()));
        result.put("productStatus", "ON_SALE".equals(item.status()) ? 0 : 1);
        result.put("productVerify", false);
        result.put("attach", attachFields(item));
        return result;
    }

    private List<Map<String, Object>> attachFields(GoodsItem item) {
        if (item.type() == GoodsType.CARD) {
            return List.of();
        }
        List<RechargeFieldItem> configured = service.rechargeFields(item);
        if (configured.isEmpty() && Boolean.TRUE.equals(item.requireRechargeAccount())) {
            configured = List.of(new RechargeFieldItem(
                0L, "account", "充值账号", "请输入充值账号", "", "text", true, 0, true, null, null
            ));
        }
        if (configured.size() > 1) {
            String labels = configured.stream()
                .map(RechargeFieldItem::label)
                .distinct()
                .reduce((left, right) -> left + " / " + right)
                .orElse("充值账号");
            configured = List.of(new RechargeFieldItem(
                0L, "account", labels + "（任填一项）", "请输入" + labels, "", "text", true, 0, true, null, null
            ));
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (RechargeFieldItem field : configured) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", 0);
            value.put("name", field.code());
            value.put("title", field.label());
            value.put("desc", field.placeholder());
            value.put("inputType", 0);
            value.put("inputCheck", Boolean.TRUE.equals(field.required()) ? 1 : 0);
            value.put("inputDataSourceType", 0);
            value.put("inputDataSource", null);
            value.put("value", null);
            fields.add(value);
        }
        return List.copyOf(fields);
    }

    private Map<String, String> rechargeFields(GoodsItem goods, Map<String, Object> attach) {
        Set<String> allowed = goods.accountTypes() == null ? Set.of() : Set.copyOf(goods.accountTypes());
        Map<String, String> fields = new LinkedHashMap<>();
        attach.forEach((key, value) -> {
            if (allowed.contains(key) && value != null) {
                fields.put(key, String.valueOf(value));
            }
        });
        return Map.copyOf(fields);
    }

    private String failureAuditMessage(Map<String, Object> payload, RuntimeException ex) {
        return "productNo=" + text(payload, "productNo")
            + ", orderNo=" + text(payload, "orderNo")
            + ", reason=" + clean(ex.getMessage());
    }

    private void notifyCancel(
        Map<String, Object> request,
        Map<String, Object> result,
        OutboundApiPrincipal principal
    ) {
        String callbackUrl = text(request, "callbackUrl");
        if (!StringUtils.hasText(callbackUrl)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("timestamp", Instant.now().getEpochSecond());
        payload.put("sign", AgisoSignatureUtil.sign(payload, merchantSecret(principal)));
        try {
            callbackClient.post(callbackUrl, payload, service.settings().timeoutSeconds());
        } catch (RuntimeException ex) {
            LOGGER.warn("Agiso cancel callback failed for {}: {}", result.get("orderNo"), ex.getMessage());
        }
    }

    private BigDecimal validateMaxAmount(Map<String, Object> payload, BigDecimal expectedCost) {
        String supplied = text(payload, "maxAmount");
        if (!StringUtils.hasText(supplied)) {
            return null;
        }
        try {
            BigDecimal maxAmount = new BigDecimal(supplied);
            BigDecimal normalized = maxAmount.stripTrailingZeros();
            int scale = Math.max(normalized.scale(), 0);
            int integerDigits = normalized.precision() - normalized.scale();
            if (scale > 4 || integerDigits > 14) {
                throw new IllegalArgumentException("maxAmount is invalid");
            }
            if (maxAmount.compareTo(expectedCost) < 0) {
                throw new AgisoMaxAmountException();
            }
            return maxAmount;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("maxAmount is invalid");
        }
    }

    private Map<String, Object> errorCode(RuntimeException ex) {
        if (ex instanceof AgisoMaxAmountException) {
            return error(1220, ex.getMessage());
        }
        if (ex instanceof AgisoAsyncCallbackRequiredException
            || ex instanceof AgisoSyncOrderTimeoutException) {
            return error(9999, ex.getMessage());
        }
        String message = clean(ex.getMessage());
        if ("goods not found".equals(message) || "goods unavailable".equals(message)) {
            return error(1100, "失败原因:商品不存在", null);
        }
        if (message.contains("signature")) return error(401, message);
        if (message.contains("timestamp")) return error(408, message);
        if (message.contains("stock")) return error(1208, message);
        if (message.contains("goods") || message.contains("product")) return error(1204, message);
        if (message.contains("order")) return error(1205, message);
        return error(500, message.isEmpty() ? "request failed" : message);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "接口调用成功");
        response.put("data", data);
        return response;
    }

    private Map<String, Object> error(int code, String message) {
        return error(code, message, Map.of());
    }

    private Map<String, Object> error(int code, String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("data", data);
        return response;
    }

    private boolean configured() {
        return enabled && StringUtils.hasText(appId) && StringUtils.hasText(appSecret);
    }

    private Object numericAppId() {
        try {
            return Long.parseLong(appId);
        } catch (NumberFormatException ex) {
            return appId;
        }
    }

    private String merchantSecret(OutboundApiPrincipal principal) {
        return appSecret + principal.credential().appSecret();
    }

    private int productType(GoodsItem item) {
        return item.type() == GoodsType.CARD ? 2 : 1;
    }

    private int apiType(GoodsItem item) {
        return item.type() == GoodsType.CARD ? API_TYPE_SYNC : API_TYPE_ASYNC;
    }

    private boolean freshTimestamp(String value) {
        try {
            long supplied = Long.parseLong(value);
            return withinTimestampTolerance(Instant.now().getEpochSecond(), supplied);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static boolean withinTimestampTolerance(long now, long supplied) {
        return supplied > now - TIMESTAMP_TOLERANCE_SECONDS
            && supplied < now + TIMESTAMP_TOLERANCE_SECONDS;
    }

    static String disableSaleChannels(List<String> channels) {
        return channels == null || channels.isEmpty() ? null : String.join(",", channels);
    }

    private boolean secureEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
            expected.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
            supplied.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private static Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("attach is invalid");
        }
    }

    private static String firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = text(values, key);
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    private static String requiredText(Map<String, Object> values, String key) {
        String value = text(values, key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String text(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Long longValue(Map<String, ?> values, String key) {
        String value = requiredText((Map<String, Object>) values, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid");
        }
    }

    private static int intValue(Map<String, ?> values, String key, int fallback) {
        String value = text(values, key);
        if (!StringUtils.hasText(value)) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static final class AgisoMaxAmountException extends IllegalArgumentException {
        private AgisoMaxAmountException() {
            super("order cost exceeds maxAmount");
        }
    }

    private static final class AgisoAsyncCallbackRequiredException extends IllegalArgumentException {
        private AgisoAsyncCallbackRequiredException() {
            super("async product requires callbackUrl");
        }
    }

    private static final class AgisoSyncOrderTimeoutException extends IllegalStateException {
        private AgisoSyncOrderTimeoutException() {
            super("synchronous product did not reach a final state within the wait limit");
        }
    }

}
