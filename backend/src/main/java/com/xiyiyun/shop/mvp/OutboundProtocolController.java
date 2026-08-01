package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.OrderStatus;
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
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

@RestController
public class OutboundProtocolController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final OutboundProtocolService service;

    public OutboundProtocolController(OutboundProtocolService service) {
        this.service = service;
    }

    // -------------------------------------------------------------- 卡速售

    @PostMapping("/api/v1/user/info")
    public Map<String, Object> kasushouUser(
        @RequestHeader(value = "UserId", required = false) String userId,
        @RequestHeader(value = "Timestamp", required = false) String timestamp,
        @RequestHeader(value = "Sign", required = false) String sign,
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        return kasushou(userId, timestamp, sign, body, request, principal -> Map.of(
            "code", 200, "msg", "ok", "data", Map.of("balance", principal.user().balance())
        ));
    }

    @PostMapping("/api/v1/goods/cate")
    public Map<String, Object> kasushouCategories(
        @RequestHeader(value = "UserId", required = false) String userId,
        @RequestHeader(value = "Timestamp", required = false) String timestamp,
        @RequestHeader(value = "Sign", required = false) String sign,
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        return kasushou(userId, timestamp, sign, body, request, principal -> Map.of(
            "code", 200, "msg", "ok", "data", categories(service.goods(principal, null, ""))
        ));
    }

    @PostMapping("/api/v1/goods/list")
    public Map<String, Object> kasushouGoods(
        @RequestHeader(value = "UserId", required = false) String userId,
        @RequestHeader(value = "Timestamp", required = false) String timestamp,
        @RequestHeader(value = "Sign", required = false) String sign,
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return kasushou(userId, timestamp, sign, payload, request, principal -> {
            List<GoodsItem> all = service.goods(principal, longValue(payload, "cate_id"), text(payload, "keyword"));
            List<GoodsItem> page = page(all, intValue(payload, "page", 1), intValue(payload, "limit", 100));
            return Map.of("code", 200, "msg", "ok", "data", Map.of(
                "total", all.size(), "list", page.stream().map(this::goodsMap).toList()
            ));
        });
    }

    @PostMapping("/api/v1/order/buy")
    public Map<String, Object> kasushouBuy(
        @RequestHeader(value = "UserId", required = false) String userId,
        @RequestHeader(value = "Timestamp", required = false) String timestamp,
        @RequestHeader(value = "Sign", required = false) String sign,
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return kasushou(userId, timestamp, sign, payload, request, principal -> {
            Map<String, Object> attach = mapValue(payload.get("attach"));
            Map<String, String> rechargeFields = new LinkedHashMap<>(stringMap(attach));
            rechargeFields.remove("recharge_account");
            OrderItem order = service.createOrder(
                principal, longValue(payload, "id"), intValue(payload, "quantity", 1),
                text(attach, "recharge_account"), text(payload, "mark"), text(payload, "external_orderno"),
                rechargeFields, clientIp(request)
            );
            return Map.of("code", 200, "msg", "ok", "data", kasushouOrder(order));
        });
    }

    @PostMapping("/api/v1/order/info")
    public Map<String, Object> kasushouOrderInfo(
        @RequestHeader(value = "UserId", required = false) String userId,
        @RequestHeader(value = "Timestamp", required = false) String timestamp,
        @RequestHeader(value = "Sign", required = false) String sign,
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        return kasushou(userId, timestamp, sign, payload, request, principal -> Map.of(
            "code", 200, "msg", "ok", "data", List.of(kasushouOrder(service.findOrder(
                principal, text(payload, "ordersn"), text(payload, "external_orderno")
            )))
        ));
    }

    private Map<String, Object> kasushou(
        String userId,
        String timestamp,
        String sign,
        Map<String, Object> body,
        HttpServletRequest request,
        Function<OutboundApiPrincipal, Map<String, Object>> action
    ) {
        Map<String, Object> payload = safe(body);
        return execute("KASUSHOU", userId, request, principal ->
            freshTimestamp(timestamp, true)
                && secureEquals(KasushouSignatureUtil.sign(timestamp, payload, principal.credential().appSecret()), sign),
            action
        );
    }

    // -------------------------------------------------------------- 咔咔云

    @PostMapping({"/dockapiv3/user/info", "/dockapiv3/goods/group", "/dockapiv3/goods/all", "/dockapiv3/order/create", "/dockapiv3/order/get"})
    public Map<String, Object> kakayun(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        String path = request.getRequestURI();
        String appKey = text(payload, "userid");
        return execute("KAKAYUN", appKey, request, principal ->
            freshTimestamp(text(payload, "timestamp"), false)
                && secureEquals(KakayunSignatureUtil.sign(payload, principal.credential().appSecret()), text(payload, "sign")),
            principal -> kakayunAction(path, payload, principal, request)
        );
    }

    private Map<String, Object> kakayunAction(
        String path,
        Map<String, Object> payload,
        OutboundApiPrincipal principal,
        HttpServletRequest request
    ) {
        if (path.endsWith("/user/info")) {
            return Map.of("code", 1, "msg", "ok", "data", Map.of("money", principal.user().balance()));
        }
        if (path.endsWith("/goods/group")) {
            return Map.of("code", 1, "msg", "ok", "data", categories(service.goods(principal, null, "")));
        }
        if (path.endsWith("/goods/all")) {
            List<GoodsItem> all = service.goods(principal, longValue(payload, "groupid"), text(payload, "goodsname"));
            return Map.of("code", 1, "msg", "ok", "count", all.size(), "data", page(
                all, intValue(payload, "page", 1), intValue(payload, "limit", 100)
            ).stream().map(this::goodsMap).toList());
        }
        if (path.endsWith("/order/create")) {
            OrderItem order = service.createOrder(
                principal, longValue(payload, "goodsid"), intValue(payload, "buynum", 1), text(payload, "attach"),
                "", text(payload, "usorderno"), Map.of(), clientIp(request)
            );
            return Map.of("code", 1, "msg", "ok", "data", kakayunOrder(order));
        }
        OrderItem order = service.findOrder(principal, text(payload, "orderno"), text(payload, "usorderno"));
        return Map.of("code", 1, "msg", "ok", "data", kakayunOrder(order));
    }

    // -------------------------------------------------------------- 福禄

    @PostMapping("/api/rechargeapi/gateway")
    public Map<String, Object> fulu(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        String appKey = text(payload, "app_key");
        return execute("FULU", appKey, request, principal -> {
            Map<String, String> signParams = stringMap(payload);
            String supplied = signParams.remove("sign");
            return secureEquals(FuluSignatureUtil.requestSign(signParams, principal.credential().appSecret()), supplied);
        }, principal -> {
            Map<String, Object> biz = parseJsonMap(text(payload, "biz_content"));
            String method = text(payload, "method");
            Object result;
            if ("merchant.balance.query".equals(method)) {
                result = Map.of("Balances", List.of(Map.of("AccountType", 1, "Balance", principal.user().balance())));
            } else if ("order.notify".equals(method)) {
                OrderItem order = service.createOrder(
                    principal, firstLong(biz, "product_id", "productId"), firstInt(biz, 1, "buy_num", "quantity", "num"),
                    firstText(biz, "charge_account", "recharge_account", "account"), "",
                    firstText(biz, "customer_order_no", "customerOrderNo", "external_order_no"), Map.of(), clientIp(request)
                );
                result = fuluOrder(order);
            } else if ("order.query".equals(method)) {
                OrderItem order = service.findOrder(
                    principal, firstText(biz, "order_id", "orderId"), firstText(biz, "customer_order_no", "customerOrderNo")
                );
                result = fuluOrder(order);
            } else {
                throw new IllegalArgumentException("unsupported method");
            }
            String resultJson = writeJson(result);
            return Map.of(
                "code", 200, "msg", "ok", "result", resultJson,
                "sign", FuluSignatureUtil.responseSign(resultJson, principal.credential().appSecret())
            );
        });
    }

    // -------------------------------------------------------------- 蜂助手

    @PostMapping({
        "/fzs-stdopen-api/api/v1/balance",
        "/fzs-stdopen-api/api/v1/sendgoods",
        "/fzs-stdopen-api/api/v1/queryorder"
    })
    public Map<String, Object> fengzhushou(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        String appKey = text(payload, "projectCode");
        String path = request.getRequestURI();
        return execute("FENGZHUSHOU", appKey, request, principal ->
            freshTimestamp(text(payload, "timestamp"), true)
                && secureEquals(FengzhushouSignatureUtil.sign(payload, principal.credential().appSecret()), text(payload, "sign")),
            principal -> {
                if (path.endsWith("/balance")) {
                    return Map.of("retcode", 0, "msg", "ok", "data", Map.of("balance", principal.user().balance()));
                }
                if (path.endsWith("/sendgoods")) {
                    OrderItem order = service.createOrder(
                        principal, longValue(payload, "skuCode"), intValue(payload, "num", 1), text(payload, "account"),
                        text(payload, "ext"), text(payload, "channelOrderNo"), Map.of(), clientIp(request)
                    );
                    return Map.of("retcode", 0, "msg", "ok", "data", fengzhushouOrder(order));
                }
                OrderItem order = service.findOrder(principal, "", text(payload, "channelOrderNo"));
                return Map.of("retcode", fengzhushouStatus(order), "msg", statusMessage(order), "data", fengzhushouOrder(order));
            }
        );
    }

    // -------------------------------------------------------------- 鼎信橙券

    @PostMapping({"/user/balance/get", "/coupon/type/list", "/coupon/type/goods/list", "/order/directCharge", "/order/get"})
    public Map<String, Object> chengquan(
        @RequestBody(required = false) Map<String, Object> body,
        HttpServletRequest request
    ) {
        Map<String, Object> payload = safe(body);
        String appKey = text(payload, "app_id");
        String path = request.getRequestURI();
        return execute("CHENGQUAN", appKey, request, principal ->
            secureEquals(ChengquanSignatureUtil.sign(payload, principal.credential().appSecret()), text(payload, "sign")),
            principal -> {
                if (path.endsWith("/balance/get")) {
                    return Map.of("code", 7000, "msg", "ok", "data", Map.of("balance", principal.user().balance()));
                }
                if (path.endsWith("/type/list")) {
                    return Map.of("code", 7000, "msg", "ok", "data", categories(service.goods(principal, null, "")));
                }
                if (path.endsWith("/type/goods/list")) {
                    List<GoodsItem> goods = service.goods(principal, firstLong(payload, "type_id", "typeId"), "");
                    return Map.of("code", 7000, "msg", "ok", "data", goods.stream().map(this::goodsMap).toList());
                }
                if (path.endsWith("/directCharge")) {
                    OrderItem order = service.createOrder(
                        principal, firstLong(payload, "product_id", "productId"), firstInt(payload, 1, "amount", "quantity"),
                        firstText(payload, "recharge_number", "rechargeNumber"), "", firstText(payload, "order_no", "orderNo"),
                        Map.of(), clientIp(request)
                    );
                    return Map.of("code", 7000, "msg", "ok", "data", chengquanOrder(order));
                }
                OrderItem order = service.findOrder(
                    principal, "", firstText(payload, "order_no", "orderNo")
                );
                return Map.of("code", 7000, "msg", "ok", "data", chengquanOrder(order));
            }
        );
    }

    // -------------------------------------------------------------- 浙江梵尘

    @PostMapping(
        value = {"/fcsearchbalance.do", "/fcuserproductprice.do", "/fcgameonlinepay.do", "/fcsearchpay.do"},
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public Map<String, Object> fanchen(@RequestParam Map<String, String> form, HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(form);
        String path = request.getRequestURI();
        String appKey = text(payload, "userid");
        List<String> signedKeys = fanchenSignedKeys(path);
        return execute("FANCHEN", appKey, request, principal ->
            secureEquals(FanchenSignatureUtil.sign(payload, signedKeys, principal.credential().appSecret()), text(payload, "sign")),
            principal -> {
                if (path.endsWith("/fcsearchbalance.do")) {
                    return Map.of("resultno", "1", "balance", principal.user().balance());
                }
                if (path.endsWith("/fcuserproductprice.do")) {
                    return Map.of("resultno", "1", "products", service.goods(principal, null, "").stream().map(this::fanchenGoods).toList());
                }
                if (path.endsWith("/fcgameonlinepay.do")) {
                    OrderItem order = service.createOrder(
                        principal, longValue(payload, "productid"), intValue(payload, "num", 1), text(payload, "account"),
                        "", text(payload, "sporderid"), Map.of(), clientIp(request)
                    );
                    return fanchenSubmitOrder(order);
                }
                return fanchenOrder(service.findOrder(principal, "", text(payload, "sporderid")));
            }
        );
    }

    // -------------------------------------------------------------- 京兆云

    @PostMapping(
        value = {"/api/customer", "/api/product-list", "/api/buy", "/api/outer-order"},
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public Map<String, Object> jingzhao(@RequestParam Map<String, String> form, HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(form);
        String path = request.getRequestURI();
        String appKey = text(payload, "customer_id");
        return execute("JINGZHAO", appKey, request, principal ->
            freshTimestamp(text(payload, "timestamp"), false)
                && secureEquals(JingzhaoSignatureUtil.sign(payload, principal.credential().appSecret()), text(payload, "sign")),
            principal -> {
                if (path.endsWith("/api/customer")) {
                    return Map.of("code", "ok", "message", "ok", "data", Map.of("balance", principal.user().balance()));
                }
                if (path.endsWith("/api/product-list")) {
                    return Map.of("code", "ok", "message", "ok", "data", service.goods(principal, null, "").stream().map(this::jingzhaoGoods).toList());
                }
                if (path.endsWith("/api/buy")) {
                    OrderItem order = service.createOrder(
                        principal, longValue(payload, "product_id"), intValue(payload, "quantity", 1),
                        text(payload, "recharge_account"), "", text(payload, "outer_order_id"), Map.of(), clientIp(request)
                    );
                    return Map.of("code", "ok", "message", "ok", "data", jingzhaoOrder(order));
                }
                OrderItem order = service.findOrder(principal, "", text(payload, "outer_order_id"));
                return Map.of("code", "ok", "message", "ok", "data", jingzhaoOrder(order));
            }
        );
    }

    // -------------------------------------------------------------- 公共流程

    private Map<String, Object> execute(
        String protocol,
        String appKey,
        HttpServletRequest request,
        Predicate<OutboundApiPrincipal> signatureVerifier,
        Function<OutboundApiPrincipal, Map<String, Object>> action
    ) {
        OutboundApiPrincipal principal = null;
        String path = request.getRequestURI();
        try {
            principal = service.authorize(protocol, appKey, path, clientIp(request));
            if (!signatureVerifier.test(principal)) {
                throw new IllegalArgumentException("invalid signature");
            }
            Map<String, Object> response = action.apply(principal);
            service.accept(principal, path);
            return response;
        } catch (RuntimeException ex) {
            if (principal != null) {
                service.reject(principal, appKey, path, ex.getMessage());
            }
            return error(protocol, ex.getMessage());
        }
    }

    private Map<String, Object> error(String protocol, String message) {
        String safeMessage = StringUtils.hasText(message) ? message : "request failed";
        return switch (protocol) {
            case "KASUSHOU" -> Map.of("code", 400, "msg", safeMessage, "data", Map.of());
            case "KAKAYUN" -> Map.of("code", 0, "msg", safeMessage, "data", Map.of());
            case "FULU" -> Map.of("code", 400, "msg", safeMessage, "result", "");
            case "FENGZHUSHOU" -> Map.of("retcode", 1000, "msg", safeMessage, "data", Map.of());
            case "CHENGQUAN" -> Map.of("code", 7001, "msg", safeMessage, "data", Map.of());
            case "FANCHEN" -> Map.of("resultno", "9999", "remark1", safeMessage);
            case "JINGZHAO" -> Map.of("code", "error", "message", safeMessage, "data", Map.of());
            default -> Map.of("code", -1, "message", safeMessage);
        };
    }

    private Map<String, Object> goodsMap(GoodsItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id());
        result.put("goods_id", item.id());
        result.put("goodsid", item.id());
        result.put("name", item.goodsName());
        result.put("goods_name", item.goodsName());
        result.put("goodsname", item.goodsName());
        result.put("goods_type", item.type() == null ? "" : item.type().name());
        result.put("type", item.type() == null ? "" : item.type().name());
        result.put("cate_id", item.categoryId());
        result.put("groupid", item.categoryId());
        result.put("category_id", item.categoryId());
        result.put("cate_name", item.categoryName());
        result.put("category_name", item.categoryName());
        result.put("price", item.price());
        result.put("goods_price", item.price());
        result.put("face_value", item.originalPrice());
        result.put("stock", item.stock());
        result.put("stock_num", item.stock());
        result.put("status", item.status());
        result.put("can_buy", true);
        result.put("can_no_buy", false);
        result.put("require_recharge_account", Boolean.TRUE.equals(item.requireRechargeAccount()));
        return result;
    }

    private List<Map<String, Object>> categories(List<GoodsItem> goods) {
        Map<Long, String> categories = new LinkedHashMap<>();
        goods.forEach(item -> categories.putIfAbsent(item.categoryId(), item.categoryName()));
        return categories.entrySet().stream().map(entry -> Map.<String, Object>of(
            "id", entry.getKey(), "name", entry.getValue() == null ? "" : entry.getValue()
        )).toList();
    }

    private Map<String, Object> kasushouOrder(OrderItem order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ordersn", order.orderNo());
        result.put("external_orderno", order.requestId());
        result.put("status", kasushouStatus(order));
        result.put("total_price", order.payAmount());
        result.put("recharge_hints", statusMessage(order));
        result.put("card_list", cards(order, "card_no", "card_password"));
        return result;
    }

    private Map<String, Object> kakayunOrder(OrderItem order) {
        return Map.of(
            "orderno", order.orderNo(), "usorderno", text(order.requestId()), "status", kakayunStatus(order),
            "money", order.payAmount(), "receipt", statusMessage(order), "cards", cards(order, "card_no", "card_pwd")
        );
    }

    private Map<String, Object> fuluOrder(OrderItem order) {
        return Map.of(
            "order_id", order.orderNo(), "customer_order_no", text(order.requestId()), "order_status", fuluStatus(order),
            "total_price", order.payAmount(), "charge_remark", statusMessage(order), "product_name", order.goodsName(),
            "card_pwds", cards(order, "card_no", "card_pwd")
        );
    }

    private Map<String, Object> fengzhushouOrder(OrderItem order) {
        return Map.of(
            "orderNo", order.orderNo(), "channelOrderNo", text(order.requestId()), "skuPrice", order.payAmount(),
            "status", fengzhushouStatus(order), "message", statusMessage(order)
        );
    }

    private Map<String, Object> chengquanOrder(OrderItem order) {
        return Map.of(
            "order_no", order.orderNo(), "external_order_no", text(order.requestId()), "status", chengquanStatus(order),
            "amount", order.payAmount(), "message", statusMessage(order), "cards", cards(order, "card_no", "card_password")
        );
    }

    private Map<String, Object> fanchenOrder(OrderItem order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultno", fanchenStatus(order));
        result.put("orderid", order.orderNo());
        result.put("sporderid", text(order.requestId()));
        result.put("ordercash", order.payAmount());
        result.put("remark1", statusMessage(order));
        result.put("cards", cards(order, "card_no", "card_password"));
        return result;
    }

    private Map<String, Object> fanchenSubmitOrder(OrderItem order) {
        Map<String, Object> result = new LinkedHashMap<>(fanchenOrder(order));
        result.put("resultno", isFailure(order.status()) ? "9" : order.status() == OrderStatus.DELIVERED ? "0" : "2");
        return result;
    }

    private Map<String, Object> jingzhaoOrder(OrderItem order) {
        return Map.of(
            "order_id", order.orderNo(), "outer_order_id", text(order.requestId()), "status", jingzhaoStatus(order),
            "amount", order.payAmount(), "message", statusMessage(order), "cards", cards(order, "card_no", "card_password")
        );
    }

    private Map<String, Object> fanchenGoods(GoodsItem item) {
        Map<String, Object> result = goodsMap(item);
        result.put("productid", item.id());
        result.put("productname", item.goodsName());
        result.put("productprice", item.price());
        return result;
    }

    private Map<String, Object> jingzhaoGoods(GoodsItem item) {
        Map<String, Object> result = goodsMap(item);
        result.put("product_id", item.id());
        result.put("product_name", item.goodsName());
        result.put("product_price", item.price());
        return result;
    }

    private List<Map<String, Object>> cards(OrderItem order, String noKey, String secretKey) {
        List<Map<String, Object>> cards = new ArrayList<>();
        List<String> items = order.deliveryItems() == null ? List.of() : order.deliveryItems();
        for (int index = 0; index < items.size(); index++) {
            String value = items.get(index);
            if (StringUtils.hasText(value)) {
                cards.add(Map.of(noKey, String.valueOf(index + 1), secretKey, value));
            }
        }
        return cards;
    }

    private int kasushouStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 3;
        if (isFailure(order.status())) return 4;
        return 2;
    }

    private int kakayunStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 5;
        if (isFailure(order.status())) return 4;
        return 3;
    }

    private int fuluStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 3;
        if (isFailure(order.status())) return 4;
        return 2;
    }

    private int fengzhushouStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 1;
        if (isFailure(order.status())) return 9;
        return 0;
    }

    private String chengquanStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return "SUCCESS";
        if (isFailure(order.status())) return "FAIL";
        return "RECHARGE";
    }

    private String fanchenStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return "1";
        if (isFailure(order.status())) return "9";
        return "2";
    }

    private int jingzhaoStatus(OrderItem order) {
        if (order.status() == OrderStatus.DELIVERED) return 200;
        if (isFailure(order.status())) return 500;
        return 101;
    }

    private boolean isFailure(OrderStatus status) {
        return status == OrderStatus.FAILED || status == OrderStatus.REFUNDED || status == OrderStatus.CANCELLED || status == OrderStatus.CLOSED;
    }

    private String statusMessage(OrderItem order) {
        return StringUtils.hasText(order.deliveryMessage()) ? order.deliveryMessage() : order.status().name();
    }

    private List<String> fanchenSignedKeys(String path) {
        if (path.endsWith("/fcsearchbalance.do")) return List.of("userid");
        if (path.endsWith("/fcuserproductprice.do")) return List.of("userid", "productid");
        if (path.endsWith("/fcsearchpay.do")) return List.of("userid", "sporderid");
        return List.of("userid", "productid", "num", "areaid", "serverid", "account", "spordertime", "sporderid");
    }

    private boolean freshTimestamp(String value, boolean milliseconds) {
        try {
            long supplied = Long.parseLong(value);
            long now = milliseconds ? System.currentTimeMillis() : Instant.now().getEpochSecond();
            long tolerance = milliseconds ? 300_000L : 300L;
            return Math.abs(now - supplied) <= tolerance;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean secureEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
            expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
            supplied.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? OBJECT_MAPPER.convertValue(map, MAP_TYPE) : Map.of();
    }

    private static Map<String, String> stringMap(Map<String, ?> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private static Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid biz_content");
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("response serialization failed");
        }
    }

    private static String text(Map<String, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String firstText(Map<String, ?> values, String... keys) {
        for (String key : keys) {
            String value = text(values, key);
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    private static Long longValue(Map<String, ?> values, String key) {
        String value = text(values, key);
        if (!StringUtils.hasText(value)) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " is invalid");
        }
    }

    private static Long firstLong(Map<String, ?> values, String... keys) {
        for (String key : keys) {
            if (StringUtils.hasText(text(values, key))) return longValue(values, key);
        }
        return null;
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

    private static int firstInt(Map<String, ?> values, int fallback, String... keys) {
        for (String key : keys) {
            if (StringUtils.hasText(text(values, key))) return intValue(values, key, fallback);
        }
        return fallback;
    }

    private static <T> List<T> page(List<T> values, int page, int limit) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(500, limit));
        int from = Math.min(values.size(), (safePage - 1) * safeLimit);
        int to = Math.min(values.size(), from + safeLimit);
        return values.subList(from, to);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
