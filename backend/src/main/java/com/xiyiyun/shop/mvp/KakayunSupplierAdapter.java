package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstExisting;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.intValue;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 咔咔云适配器。签名沿用 {@link KakayunSignatureUtil}，拼接方式零改动。 */
@Component
public class KakayunSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("kakayun");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.KAKAYUN;
    }

    @Override
    public boolean supportsRemoteGoodsSync() {
        return true;
    }

    @Override
    public boolean supportsSingleGoodsQuery() {
        return true;
    }

    @Override
    public SupplierItem testConnection(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }
        JsonNode root = postJson(context, "/dockapiv3/user/info", Map.of(), "test connection", true);
        ensureOk(root, "test connection");
        return context.supplier().withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public SupplierItem refreshBalance(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        JsonNode root = postJson(context, "/dockapiv3/user/info", Map.of(), "balance refresh", true);
        ensureOk(root, "balance refresh");
        return context.supplier().withBalance(balance(root));
    }

    @Override
    public UpstreamSubmitResult submitOrder(SupplierCallContext context, OrderItem order, GoodsChannelItem channel) {
        SupplierItem supplier = context.supplier();
        validateCredentials(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsid", SupplierGoodsId.of(channel.supplierGoodsId()));
        body.put("buynum", order.quantity() == null ? 1 : order.quantity());
        body.put("usorderno", order.orderNo());
        body.put("maxmoney", order.payAmount());
        if (StringUtils.hasText(order.rechargeAccount())) {
            body.put("attach", order.rechargeAccount().trim());
        }
        if (StringUtils.hasText(context.callbackUrl())) {
            body.put("callbackurl", context.callbackUrl());
        }
        JsonNode root = postJson(context, "/dockapiv3/order/create", body, "order submit", false);
        int code = intValue(root.path("code"), -1);
        if (code != 1 && code != 9999) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("kakayun", "order submit", String.valueOf(code),
                "kakayun order submit failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(
            textValue(data, "orderno", "orderNo", "order_no"),
            textValue(root, "orderno", "orderNo", "order_no"),
            ""
        );
        String externalOrderNo = firstText(
            textValue(data, "usorderno", "usOrderNo", "external_order_no"),
            order.orderNo(),
            ""
        );
        String totalPrice = textValue(data, "money", "total_price", "totalPrice", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        if (StringUtils.hasText(externalOrderNo)) {
            deliveryItems.add("外部订单号：" + externalOrderNo);
        }
        String prefix = code == 9999 ? "上游返回处理中，已提交待查单" : "上游已接单，等待处理";
        String priceText = StringUtils.hasText(totalPrice) ? "，上游金额：" + totalPrice : "";
        String attemptMessage = prefix
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + priceText;
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstreamOrderNo);
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usorderno", order.orderNo());
        JsonNode root = postJson(context, "/dockapiv3/order/get", body, "order info sync", true);
        ensureOk(root, "order info sync");
        JsonNode data = root.path("data");
        JsonNode node = data.isArray() && data.size() > 0 ? data.get(0) : data;
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("kakayun order info sync failed: data is empty");
        }
        List<String> cards = new ArrayList<>();
        JsonNode cardList = firstExisting(node, "cards", "cardlist", "cardList");
        if (cardList != null && cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = textValue(card, "card_no", "cardNo", "cardno");
                String cardPassword = textValue(card, "card_pwd", "cardPwd", "card_password", "password");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        } else if (cardList != null && StringUtils.hasText(cardList.asText())) {
            cards.add(cardList.asText());
        }
        int status = intValue(node.path("status"), 0);
        String hints = firstText(
            textValue(node, "receipt", "refundreceipt", "message", "msg"),
            textValue(root, "msg", "message"),
            ""
        );
        return new UpstreamOrderSnapshot(
            textValue(node, "orderno", "orderNo", "order_no"),
            firstText(textValue(node, "usorderno", "usOrderNo"), order.orderNo(), ""),
            localStatus(status, fallback),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            optionalDecimalValue(node, "money", "total_price", "amount"),
            "",
            List.copyOf(cards),
            abbreviate(node.toString(), 1200)
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

        JsonNode groupRoot = postJson(context, "/dockapiv3/goods/group", Map.of(), "category sync", true);
        ensureOk(groupRoot, "category sync");
        List<Map<String, Object>> categories = context.goods().kakayunCategories(groupRoot.path("data"));
        Map<String, String> categoryNames = context.goods().categoryNames(categories);
        String selectedCategoryId = categoryId == null || categoryId == 0 ? "" : String.valueOf(categoryId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page);
        body.put("limit", limit);
        if (StringUtils.hasText(keyword)) {
            body.put("goodsname", keyword.trim());
        }
        if (StringUtils.hasText(selectedCategoryId)) {
            body.put("groupid", selectedCategoryId);
        }
        JsonNode listRoot = postJson(context, "/dockapiv3/goods/all", body, "goods list sync", true);
        ensureOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.isArray() ? data : firstExisting(data, "list", "goods", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("kakayun goods list sync failed: data list is missing");
        }
        int total = intValue(firstExisting(listRoot, "count", "total"), listNode.size());
        if (data.isObject()) {
            total = intValue(firstExisting(data, "count", "total"), total);
        }
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            items.add(context.goods().kakayunItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName));
        }
        return new RemoteGoodsSyncResult(
            item.id(),
            OffsetDateTime.now(),
            total,
            items,
            categories,
            page,
            limit,
            "synced " + items.size() + " kakayun goods from remote total " + total
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("kakayun baseUrl is required");
        }
        if (!StringUtils.hasText(identity(item))) {
            throw new IllegalArgumentException("kakayun userid is required");
        }
        if (!StringUtils.hasText(defaultText(context.apiKey(), "").trim())) {
            throw new IllegalArgumentException("kakayun key is required");
        }
    }

    static String identity(SupplierItem item) {
        return firstText(item.userId(), item.appId(), item.appKey());
    }

    private JsonNode postJson(
        SupplierCallContext context,
        String path,
        Map<String, Object> bodyObject,
        String action,
        boolean idempotent
    ) {
        SupplierItem item = context.supplier();
        Map<String, Object> body = new LinkedHashMap<>();
        if (bodyObject != null) {
            body.putAll(bodyObject);
        }
        body.put("userid", identity(item));
        body.put("timestamp", Instant.now().getEpochSecond());
        body.put("sign", KakayunSignatureUtil.sign(body, defaultText(context.apiKey(), "").trim()));
        URI uri = KasushouSupplierAdapter.uri(item.baseUrl(), path);
        String payload = KakayunSignatureUtil.jsonBody(body);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 1) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("kakayun", action, String.valueOf(code),
                "kakayun " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private BigDecimal balance(JsonNode root) {
        BigDecimal balance = optionalDecimalValue(root, "money", "balance", "user_money", "account_balance");
        if (balance != null) {
            return balance;
        }
        JsonNode data = root.path("data");
        balance = optionalDecimalValue(data, "money", "balance", "user_money", "account_balance");
        if (balance != null) {
            return balance;
        }
        throw new IllegalStateException("kakayun balance refresh failed: balance field is missing");
    }

    static OrderStatus localStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 5 -> OrderStatus.DELIVERED;
            case 2, 4 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(int status) {
        return switch (status) {
            case 0 -> "PAID";
            case 1 -> "PAID_OR_CARD_DONE";
            case 2 -> "UNPAID";
            case 3 -> "PROCESSING";
            case 4 -> "FAILED";
            case 5 -> "DELIVERED";
            default -> "UNKNOWN";
        };
    }

    static String deliveryMessage(int status, String hints) {
        String statusLabel = switch (status) {
            case 0, 1 -> "上游已受理";
            case 2 -> "上游未付款或失败";
            case 3 -> "上游正在处理";
            case 4 -> "上游处理失败";
            case 5 -> "上游交易成功";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
