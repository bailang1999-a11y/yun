package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.intValue;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;
import static com.xiyiyun.shop.mvp.SupplierJson.trimTrailingSlash;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 卡速售适配器。签名沿用 {@link KasushouSignatureUtil}，拼接方式零改动。
 *
 * <p>HTTP 层差异（Sign / Timestamp / UserId 三个自定义请求头）通过
 * {@link SupplierHttpRequest#extraHeaders()} 声明式传入，
 * {@link SupplierHttpClient} 内部不含任何供应商名判断。</p>
 */
@Component
public class KasushouSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("kasushou");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.KASUSHOU;
    }

    @Override
    public boolean supportsRemoteGoodsSync() {
        return true;
    }

    @Override
    public boolean supportsSingleGoodsQuery() {
        return true;
    }

    /** 原 fetchIntegratedRemoteGoods 里卡速售一路没有 Math.min(limit, 100)，故不夹取。 */
    @Override
    public int maxRemoteGoodsPageSize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public SupplierItem testConnection(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }
        JsonNode root = postJson(context, "/api/v1/user/info", Map.of(), "test connection", true);
        ensureOk(root, "test connection");
        return item.withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public SupplierItem refreshBalance(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        JsonNode root = postJson(context, "/api/v1/user/info", Map.of(), "balance refresh", true);
        ensureOk(root, "balance refresh");
        return context.supplier().withBalance(balance(root));
    }

    @Override
    public UpstreamSubmitResult submitOrder(
        SupplierCallContext context,
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price
    ) {
        SupplierItem supplier = context.supplier();
        validateCredentials(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", SupplierGoodsId.of(channel.supplierGoodsId()));
        if (StringUtils.hasText(context.callbackUrl())) {
            body.put("url", context.callbackUrl());
        }
        body.put("external_orderno", order.orderNo());
        body.put("mark", defaultText(order.buyerRemark(), ""));
        body.put("quantity", order.quantity() == null ? 1 : order.quantity());

        Map<String, Object> attach = new LinkedHashMap<>();
        if (StringUtils.hasText(order.rechargeAccount())) {
            attach.put("recharge_account", order.rechargeAccount().trim());
        }
        if (!attach.isEmpty()) {
            body.put("attach", attach);
        }

        JsonNode root = postJson(context, "/api/v1/order/buy", body, "order submit", false);
        ensureOk(root, "order submit");
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(
            textValue(data, "ordersn", "order_sn", "orderNo", "order_no"),
            textValue(root, "ordersn", "order_sn", "orderNo", "order_no"),
            ""
        );
        String externalOrderNo = firstText(
            textValue(data, "external_orderno", "externalOrderNo", "external_order_no"),
            order.orderNo(),
            ""
        );
        String totalPrice = textValue(data, "total_price", "totalPrice", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        if (StringUtils.hasText(externalOrderNo)) {
            deliveryItems.add("外部订单号：" + externalOrderNo);
        }
        String priceText = StringUtils.hasText(totalPrice) ? "，上游金额：" + totalPrice : "";
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + priceText;
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstreamOrderNo);
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("external_orderno", order.orderNo());
        body.put("ordersn", "");
        body.put("day", "0");
        JsonNode root = postJson(context, "/api/v1/order/info", body, "order info sync", true);
        ensureOk(root, "order info sync");
        JsonNode data = root.path("data");
        JsonNode node = data.isArray() && data.size() > 0 ? data.get(0) : data;
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("kasushou order info sync failed: data is empty");
        }
        List<String> cards = new ArrayList<>();
        JsonNode cardList = node.path("card_list");
        if (cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = textValue(card, "card_no", "cardNo");
                String cardPassword = textValue(card, "card_password", "cardPassword", "password");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        int status = intValue(node.path("status"), 0);
        String hints = textValue(node, "recharge_hints", "rechargeHints", "message", "msg");
        return new UpstreamOrderSnapshot(
            textValue(node, "ordersn", "order_sn", "orderNo", "order_no"),
            textValue(node, "external_orderno", "externalOrderNo", "external_order_no"),
            localStatus(status, fallback),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            optionalDecimalValue(node, "total_price", "totalPrice", "amount"),
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

        JsonNode cateRoot = postJson(context, "/api/v1/goods/cate", Map.of(), "category sync", true);
        ensureOk(cateRoot, "category sync");
        List<Map<String, Object>> categories = context.goods().kasushouCategories(cateRoot.path("data"));
        Map<String, String> categoryNames = context.goods().categoryNames(categories);
        String selectedCategoryId = categoryId == null || categoryId == 0 ? "" : String.valueOf(categoryId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

        Map<String, Object> listBody = new LinkedHashMap<>();
        listBody.put("cate_id", categoryId == null ? "" : categoryId);
        listBody.put("keyword", defaultText(keyword, ""));
        listBody.put("limit", limit);
        listBody.put("page", page);

        JsonNode listRoot = postJson(context, "/api/v1/goods/list", listBody, "goods list sync", true);
        ensureOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.path("list");
        if (!listNode.isArray()) {
            throw new IllegalStateException("kasushou goods list sync failed: data.list is missing");
        }
        int total = intValue(data.path("total"), listNode.size());
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            items.add(context.goods().kasushouItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName));
        }
        return new RemoteGoodsSyncResult(
            item.id(),
            OffsetDateTime.now(),
            total,
            items,
            categories,
            page,
            limit,
            "synced " + items.size() + " kasushou goods from remote total " + total
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("kasushou baseUrl is required");
        }
        if (!StringUtils.hasText(identity(item))) {
            throw new IllegalArgumentException("kasushou appId is required");
        }
        if (!StringUtils.hasText(defaultText(context.apiKey(), "").trim())) {
            throw new IllegalArgumentException("kasushou apiKey is required");
        }
    }

    static String identity(SupplierItem item) {
        return firstText(item.userId(), item.appId(), item.appKey());
    }

    private JsonNode postJson(
        SupplierCallContext context,
        String path,
        Object bodyObject,
        String action,
        boolean idempotent
    ) {
        SupplierItem item = context.supplier();
        String apiKey = defaultText(context.apiKey(), "").trim();
        String body = KasushouSignatureUtil.sortedJsonBody(bodyObject);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = KasushouSignatureUtil.signRaw(timestamp, body, apiKey);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Sign", sign);
        headers.put("Timestamp", timestamp);
        headers.put("UserId", identity(item));
        URI uri = uri(item.baseUrl(), path);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, body, headers, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, body, headers, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    static URI uri(String baseUrl, String path) {
        return URI.create(trimTrailingSlash(baseUrl.trim()) + path);
    }

    private void ensureOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 200) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("kasushou", action, String.valueOf(code),
                "kasushou " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private BigDecimal balance(JsonNode root) {
        BigDecimal balance = optionalDecimalValue(root,
            "balance", "money", "user_money", "userMoney", "amount", "account_balance", "accountBalance");
        if (balance != null) {
            return balance;
        }
        JsonNode data = root.path("data");
        balance = optionalDecimalValue(data,
            "balance", "money", "user_money", "userMoney", "amount", "account_balance", "accountBalance");
        if (balance != null) {
            return balance;
        }
        throw new IllegalStateException("kasushou balance refresh failed: balance field is missing");
    }

    static OrderStatus localStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 3 -> OrderStatus.DELIVERED;
            case 4, 5, -1 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "PENDING";
            case 2 -> "PROCESSING";
            case 3 -> "DELIVERED";
            case 4 -> "CANCELLED";
            case 5 -> "REFUNDED";
            case -1 -> "UNPAID";
            default -> "UNKNOWN";
        };
    }

    static String deliveryMessage(int status, String hints) {
        String statusLabel = switch (status) {
            case 1 -> "上游等待处理";
            case 2 -> "上游正在处理";
            case 3 -> "上游交易成功";
            case 4 -> "上游已取消交易";
            case 5 -> "上游已退款";
            case -1 -> "上游未支付";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
