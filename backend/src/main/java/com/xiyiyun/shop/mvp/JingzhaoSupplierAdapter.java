package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.decimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstExisting;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.intValue;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;
import static com.xiyiyun.shop.mvp.SupplierJson.trimTrailingSlash;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 京兆云适配器。签名与表单体沿用 {@link JingzhaoSignatureUtil}，拼接方式零改动。
 *
 * <p>传输为 {@code application/x-www-form-urlencoded; charset=UTF-8}，
 * 通过 {@link SupplierHttpProfile#form} 声明。</p>
 */
@Component
public class JingzhaoSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.form("jingzhao", StandardCharsets.UTF_8);

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.JINGZHAO;
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
        return refreshBalance(context);
    }

    @Override
    public SupplierItem refreshBalance(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(item.baseUrl())) {
            return item.withBalance(item.balance() == null ? BigDecimal.ZERO : item.balance());
        }
        Map<String, Object> body = baseParams(context);
        body.put("sign", JingzhaoSignatureUtil.sign(body, key(context)));
        JsonNode root = post(context, "/api/customer", body, "balance refresh", true);
        ensureOk(root, "balance refresh");
        return item.withBalance(decimalValue(root.path("data"), "balance"));
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
        body.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("quantity", order.quantity() == null ? 1 : order.quantity());
        body.put("outer_order_id", order.orderNo());
        body.put("safe_cost", price.totalCost());
        if (StringUtils.hasText(order.rechargeAccount())) {
            body.put("recharge_account", order.rechargeAccount().trim());
        }
        if (StringUtils.hasText(context.callbackUrl())) {
            body.put("notify_url", context.callbackUrl());
        }
        if (StringUtils.hasText(order.orderIp())) {
            body.put("client_ip", order.orderIp().trim());
        }
        body.put("sign", JingzhaoSignatureUtil.sign(body, key(context)));

        JsonNode root = post(context, "/api/buy", body, "order submit", false);
        ensureOk(root, "order submit");
        UpstreamOrderSnapshot upstream = snapshotFromNode(root.path("data"), order.orderNo(), null);
        List<String> deliveryItems = upstream.mergedDeliveryItems(List.of());
        String attemptMessage = "上游已接单，状态：" + upstream.upstreamStatusLabel()
            + (StringUtils.hasText(upstream.upstreamOrderNo()) ? "，上游订单号：" + upstream.upstreamOrderNo() : "")
            + (upstream.totalPrice() == null ? "" : "，上游金额：" + upstream.totalPrice());
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstream.upstreamOrderNo());
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = baseParams(context);
        body.put("outer_order_id", order.orderNo());
        body.put("sign", JingzhaoSignatureUtil.sign(body, key(context)));
        JsonNode root = post(context, "/api/outer-order", body, "order info sync", true);
        ensureOk(root, "order info sync");
        return snapshotFromNode(root.path("data"), order.orderNo(), fallback);
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
        Map<String, Object> body = baseParams(context);
        body.put("sign", JingzhaoSignatureUtil.sign(body, key(context)));
        JsonNode root = post(context, "/api/product-list", body, "goods list sync", true);
        ensureOk(root, "goods list sync");
        JsonNode listNode = root.path("data").isArray()
            ? root.path("data")
            : firstExisting(root.path("data"), "list", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("jingzhao goods list sync failed: data list is missing");
        }
        List<RemoteGoodsItem> allItems = new ArrayList<>();
        Map<String, String> categoriesByType = new LinkedHashMap<>();
        for (JsonNode node : listNode) {
            RemoteGoodsItem remote = context.goods().jingzhaoItem(item.id(), node);
            categoriesByType.putIfAbsent(remote.goodsType(), context.goods().jingzhaoGoodsTypeLabel(remote.goodsType()));
            if (context.goods().matchesKeyword(remote, keyword)) {
                allItems.add(remote);
            }
        }
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(allItems.size(), from + limit);
        List<RemoteGoodsItem> items = from >= allItems.size() ? List.of() : allItems.subList(from, to);
        List<Map<String, Object>> categories = categoriesByType.entrySet().stream()
            .map(entry -> {
                Map<String, Object> category = new LinkedHashMap<>();
                category.put("id", entry.getKey());
                category.put("name", entry.getValue());
                return category;
            })
            .toList();
        return new RemoteGoodsSyncResult(
            item.id(),
            OffsetDateTime.now(),
            allItems.size(),
            List.copyOf(items),
            categories,
            page,
            limit,
            "synced " + items.size() + " jingzhao goods from remote total " + allItems.size()
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("jingzhao baseUrl is required");
        }
        if (!StringUtils.hasText(customerId(item))) {
            throw new IllegalArgumentException("jingzhao customer_id is required");
        }
        if (!StringUtils.hasText(key(context))) {
            throw new IllegalArgumentException("jingzhao key is required");
        }
    }

    static String customerId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private static String key(SupplierCallContext context) {
        return defaultText(context.apiKey(), "").trim();
    }

    private Map<String, Object> baseParams(SupplierCallContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", customerId(context.supplier()));
        body.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        return body;
    }

    private JsonNode post(
        SupplierCallContext context,
        String path,
        Map<String, Object> body,
        String action,
        boolean idempotent
    ) {
        validateCredentials(context);
        URI uri = URI.create(trimTrailingSlash(context.baseUrl()) + path);
        String payload = JingzhaoSignatureUtil.formBody(body == null ? Map.of() : body);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action) {
        String code = textValue(root, "code");
        if (!"ok".equalsIgnoreCase(defaultText(code, ""))) {
            String message = textValue(root, "message", "msg", "error");
            throw new SupplierBusinessException("jingzhao", action, code,
                "jingzhao " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    /**
     * 异步回调报文 -> 归一化快照。原 handleJingzhaoOrderCallback 用的
     * jingzhaoOrderStatusFromNode 与主动查询是同一段解析，这里同样复用 snapshotFromNode。
     */
    static UpstreamOrderSnapshot callbackSnapshot(JsonNode statusNode, String fallbackExternalOrderNo) {
        return snapshotFromNode(statusNode, fallbackExternalOrderNo, null);
    }

    private static UpstreamOrderSnapshot snapshotFromNode(JsonNode node, String fallbackExternalOrderNo, OrderStatus fallback) {
        JsonNode safeNode = node == null || node.isMissingNode() || node.isNull()
            ? SupplierJson.MAPPER.createObjectNode()
            : node;
        List<String> cards = new ArrayList<>();
        JsonNode cardList = firstExisting(safeNode, "cards", "cardList", "card_list");
        if (cardList != null && cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = firstText(textValue(card, "card_no", "cardNo", "no"), "", "");
                String cardPassword = firstText(textValue(card, "card_password", "cardPassword", "password"), "", "");
                String expiredAt = textValue(card, "expired_at", "expiredAt");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line) && StringUtils.hasText(expiredAt)) {
                    line += " 有效期：" + expiredAt;
                }
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        JsonNode ticketList = firstExisting(safeNode, "tickets", "ticketList", "ticket_list");
        if (ticketList != null && ticketList.isArray()) {
            for (JsonNode ticket : ticketList) {
                String value = firstText(textValue(ticket, "ticket", "url"), textValue(ticket, "no"), "");
                String expiredAt = textValue(ticket, "expired_at", "expiredAt");
                String line = StringUtils.hasText(value) ? "卡券：" + value : "";
                if (StringUtils.hasText(line) && StringUtils.hasText(expiredAt)) {
                    line += " 有效期：" + expiredAt;
                }
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        int status = intValue(firstExisting(safeNode, "state", "status"), 501);
        String hints = firstText(
            textValue(safeNode, "state_info", "stateInfo", "recharge_info", "rechargeInfo", "message", "msg"),
            "",
            ""
        );
        return new UpstreamOrderSnapshot(
            firstText(textValue(safeNode, "order_id", "orderId", "id"), "", ""),
            firstText(textValue(safeNode, "outer_order_id", "outerOrderId"), fallbackExternalOrderNo, ""),
            localStatus(status, fallback),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            optionalDecimalValue(safeNode, "total_price", "totalPrice", "product_price", "productPrice"),
            "",
            List.copyOf(cards),
            abbreviate(safeNode.toString(), 1200)
        );
    }

    static OrderStatus localStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 200 -> OrderStatus.DELIVERED;
            case 500 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(int status) {
        return switch (status) {
            case 100 -> "WAITING";
            case 101 -> "PROCESSING";
            case 200 -> "SUCCESS";
            case 500 -> "FAILED";
            case 501 -> "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    static String deliveryMessage(int status, String hints) {
        String statusLabel = switch (status) {
            case 100 -> "上游等待发货";
            case 101 -> "上游正在充值";
            case 200 -> "上游交易成功";
            case 500 -> "上游交易失败";
            case 501 -> "上游状态未知";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
