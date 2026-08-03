package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstExisting;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.intValue;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;
import static com.xiyiyun.shop.mvp.SupplierJson.trimTrailingSlash;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 鼎信橙券适配器。签名沿用 {@link ChengquanSignatureUtil}，拼接方式零改动。 */
@Component
public class ChengquanSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("chengquan");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.CHENGQUAN;
    }

    @Override
    public boolean supportsRemoteGoodsSync() {
        return true;
    }

    @Override
    public boolean supportsSingleGoodsQuery() {
        return false;
    }

    @Override
    public SupplierItem testConnection(SupplierCallContext context) {
        return refreshBalance(context).withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public SupplierItem refreshBalance(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        Map<String, Object> body = baseParams(context);
        body.put("sign", ChengquanSignatureUtil.sign(body, secret(context)));
        JsonNode root = postJson(context, "/user/balance/get", body, "balance refresh", true);
        ensureOk(root, "balance refresh");
        BigDecimal balance = optionalDecimalValue(root.path("data"), "balance", "money", "amount");
        if (balance == null) {
            balance = optionalDecimalValue(root, "balance", "money", "amount");
        }
        if (balance == null) {
            throw new IllegalStateException("chengquan balance refresh failed: balance field is missing");
        }
        return context.supplier().withBalance(balance).withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public UpstreamSubmitResult submitOrder(
        SupplierCallContext context,
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price
    ) {
        SupplierItem supplier = context.supplier();
        Map<String, Object> body = baseParams(context);
        body.put("order_no", order.orderNo());
        body.put("recharge_number", defaultText(order.rechargeAccount(), "").trim());
        body.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("amount", order.quantity() == null ? 1 : order.quantity());
        body.put("version", "v1");
        if (StringUtils.hasText(context.callbackUrl())) {
            body.put("notify_url", context.callbackUrl());
        }
        body.put("sign", ChengquanSignatureUtil.sign(body, secret(context)));

        JsonNode root = postJson(context, "/order/directCharge", body, "order submit", false);
        ensureOk(root, "order submit");
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(
            textValue(data, "order_no", "orderNo", "order_id", "orderId"),
            textValue(root, "order_no", "orderNo"),
            ""
        );
        String status = firstText(textValue(data, "status", "order_status", "orderStatus"), "RECHARGE", "");
        String amount = firstText(textValue(data, "amount", "money", "price"), "", "");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        deliveryItems.add("外部订单号：" + order.orderNo());
        String attemptMessage = "上游已接单，状态：" + status
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + (StringUtils.hasText(amount) ? "，上游金额：" + amount : "");
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstreamOrderNo);
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = baseParams(context);
        body.put("order_no", order.orderNo());
        body.put("sign", ChengquanSignatureUtil.sign(body, secret(context)));
        JsonNode root = postJson(context, "/order/get", body, "order info sync", true);
        ensureOk(root, "order info sync");
        JsonNode data = root.path("data");
        String status = firstText(
            textValue(data, "status", "order_status", "orderStatus"),
            textValue(root, "status"),
            "RECHARGE"
        );
        String hints = firstText(
            textValue(data, "message", "msg", "remark"),
            textValue(root, "message", "msg"),
            ""
        );
        return new UpstreamOrderSnapshot(
            firstText(textValue(data, "order_no", "orderNo", "order_id", "orderId"), order.orderNo(), ""),
            order.orderNo(),
            localStatus(status, fallback),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            optionalDecimalValue(data, "amount", "money", "price"),
            "",
            List.of(),
            abbreviate(root.toString(), 1200)
        );
    }

    @Override
    public RemoteGoodsSyncResult fetchRemoteGoods(
        SupplierCallContext context,
        Long categoryId,
        String keyword,
        int page,
        int limit
    ) {
        SupplierItem item = context.supplier();
        validateCredentials(context);
        Map<String, Object> typeBody = baseParams(context);
        typeBody.put("sign", ChengquanSignatureUtil.sign(typeBody, secret(context)));
        JsonNode typeRoot = postJson(context, "/coupon/type/list", typeBody, "category sync", true);
        ensureOk(typeRoot, "category sync");
        JsonNode typeList = firstExisting(typeRoot.path("data"), "list", "records", "items", "data");
        if (typeList == null || !typeList.isArray()) {
            typeList = typeRoot.path("data").isArray() ? typeRoot.path("data") : SupplierJson.MAPPER.createArrayNode();
        }
        List<Map<String, Object>> categories = context.goods().toMapList(typeList);
        Map<String, String> categoryNames = context.goods().categoryNames(categories);
        String selectedCategoryId = categoryId == null || categoryId == 0 ? "" : String.valueOf(categoryId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

        Map<String, Object> body = baseParams(context);
        body.put("page", page);
        body.put("page_size", limit);
        if (StringUtils.hasText(selectedCategoryId)) {
            body.put("type_id", selectedCategoryId);
        }
        body.put("sign", ChengquanSignatureUtil.sign(body, secret(context)));
        JsonNode listRoot = postJson(context, "/coupon/type/goods/list", body, "goods list sync", true);
        ensureOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.isArray() ? data : firstExisting(data, "list", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("chengquan goods list sync failed: data list is missing");
        }
        int total = intValue(firstExisting(data, "total", "count"), listNode.size());
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            RemoteGoodsItem remote = context.goods()
                .chengquanItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName);
            if (context.goods().matchesKeyword(remote, keyword)) {
                items.add(remote);
            }
        }
        return new RemoteGoodsSyncResult(
            item.id(),
            OffsetDateTime.now(),
            total,
            items,
            categories,
            page,
            limit,
            "synced " + items.size() + " chengquan goods from remote total " + total
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("chengquan baseUrl is required");
        }
        if (!StringUtils.hasText(appId(item))) {
            throw new IllegalArgumentException("chengquan app_id is required");
        }
        if (!StringUtils.hasText(secret(context))) {
            throw new IllegalArgumentException("chengquan key is required");
        }
    }

    static String appId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private static String secret(SupplierCallContext context) {
        return defaultText(context.apiKey(), "").trim();
    }

    private Map<String, Object> baseParams(SupplierCallContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", appId(context.supplier()));
        body.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        return body;
    }

    private JsonNode postJson(
        SupplierCallContext context,
        String path,
        Map<String, Object> body,
        String action,
        boolean idempotent
    ) {
        validateCredentials(context);
        String payload;
        try {
            payload = SupplierJson.MAPPER.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("chengquan " + action + " failed: invalid JSON payload or response");
        }
        URI uri = URI.create(trimTrailingSlash(context.baseUrl()) + path);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action) {
        int code = intValue(firstExisting(root, "code", "retcode"), -1);
        if (code != 7000 && code != 0) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("chengquan", action, String.valueOf(code),
                "chengquan " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 异步回调报文 -> 归一化快照。字段口径与原 handleChengquanOrderCallback 里
     * 直接 new ChengquanOrderStatus(...) 完全一致（橙券回调不带卡密）。
     */
    static UpstreamOrderSnapshot callbackSnapshot(
        String upstreamOrderNo,
        String externalOrderNo,
        String status,
        String hints,
        BigDecimal totalPrice,
        String rawResponse
    ) {
        return new UpstreamOrderSnapshot(
            upstreamOrderNo,
            externalOrderNo,
            localStatus(status, null),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            totalPrice,
            "",
            List.of(),
            rawResponse
        );
    }

    static OrderStatus localStatus(String upstreamStatus, OrderStatus fallback) {
        String normalized = normalize(upstreamStatus);
        if ("success".equals(normalized)) {
            return OrderStatus.DELIVERED;
        }
        if ("failure".equals(normalized) || "fail".equals(normalized)) {
            return OrderStatus.FAILED;
        }
        return fallback == null ? OrderStatus.PROCURING : fallback;
    }

    static String statusLabel(String status) {
        String normalized = normalize(status);
        if ("success".equals(normalized)) {
            return "SUCCESS";
        }
        if ("failure".equals(normalized) || "fail".equals(normalized)) {
            return "FAILURE";
        }
        if ("recharge".equals(normalized)) {
            return "RECHARGE";
        }
        return StringUtils.hasText(status) ? status : "UNKNOWN";
    }

    static String deliveryMessage(String status, String hints) {
        String statusLabel = switch (normalize(status)) {
            case "success" -> "上游充值成功";
            case "failure", "fail" -> "上游充值失败";
            case "recharge" -> "上游充值中";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
