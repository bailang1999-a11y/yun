package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstExisting;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;
import static com.xiyiyun.shop.mvp.SupplierJson.trimTrailingSlash;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 浙江梵尘适配器。签名沿用 {@link FanchenSignatureUtil}，参与签名的字段顺序零改动。
 *
 * <p><b>编码差异</b>：梵尘全链路使用 GBK（表单编码 + 请求体 + 响应解码），
 * 这一差异通过 {@link SupplierHttpProfile#form(String, Charset)} 声明，
 * {@link SupplierHttpClient} 内部不含任何 charset 的供应商判断。</p>
 */
@Component
public class FanchenSupplierAdapter implements SupplierAdapter {
    static final Charset GBK_CHARSET = Charset.forName("GBK");
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.form("fanchen", GBK_CHARSET);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.FANCHEN;
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
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid"), key(context)));
        JsonNode root = post(context, "/fcsearchbalance.do", body, "balance refresh", true);
        ensureOk(root, "balance refresh", Set.of("1"));
        BigDecimal balance = optionalDecimalValue(root, "balance", "fundbalance", "fundBalance");
        if (balance == null) {
            throw new IllegalStateException("fanchen balance refresh failed: balance field is missing");
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
        body.put("productid", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("num", String.valueOf(order.quantity() == null ? 1 : order.quantity()));
        body.put("areaid", "");
        body.put("serverid", "");
        body.put("account", defaultText(order.rechargeAccount(), "").trim());
        body.put("spordertime", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(ZonedDateTime.now(CHINA_ZONE)));
        body.put("sporderid", order.orderNo());
        body.put("sign", FanchenSignatureUtil.sign(
            body,
            List.of("userid", "productid", "num", "areaid", "serverid", "account", "spordertime", "sporderid"),
            key(context)
        ));
        if (StringUtils.hasText(context.callbackUrl())) {
            body.put("back_url", context.callbackUrl());
        }
        body.put("checkprice", price.totalCost());

        JsonNode root = post(context, "/fcgameonlinepay.do", body, "order submit", false);
        String code = textValue(root, "resultno");
        if (!Set.of("0", "2").contains(code)) {
            String message = textValue(root, "remark1", "msg", "message");
            throw new SupplierBusinessException("fanchen", "order submit", code,
                "fanchen order submit failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
        String upstreamOrderNo = textValue(root, "orderid", "orderId");
        String amount = textValue(root, "ordercash", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        deliveryItems.add("外部订单号：" + order.orderNo());
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + (StringUtils.hasText(amount) ? "，上游金额：" + amount : "");
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstreamOrderNo);
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = baseParams(context);
        body.put("sporderid", order.orderNo());
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid", "sporderid"), key(context)));
        JsonNode root = post(context, "/fcsearchpay.do", body, "order info sync", true);
        String code = textValue(root, "resultno");
        String hints = firstText(
            textValue(root, "remark1", "msg", "message"),
            textValue(root, "productname", "productName"),
            ""
        );
        return new UpstreamOrderSnapshot(
            textValue(root, "orderid", "orderId"),
            firstText(textValue(root, "sporderid", "sporderId"), order.orderNo(), ""),
            localStatus(code, fallback),
            statusLabel(code),
            deliveryMessage(code, hints),
            hints,
            optionalDecimalValue(root, "ordercash", "amount"),
            "",
            cards(root),
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
        Map<String, Object> body = baseParams(context);
        body.put("productid", "");
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid", "productid"), key(context)));
        JsonNode root = post(context, "/fcuserproductprice.do", body, "goods list sync", true);
        ensureOk(root, "goods list sync", Set.of("1"));
        JsonNode listNode = firstExisting(root, "products", "data", "list", "items");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("fanchen goods list sync failed: products is missing");
        }
        List<RemoteGoodsItem> allItems = new ArrayList<>();
        Map<String, String> categoryNames = new LinkedHashMap<>();
        for (JsonNode node : listNode) {
            String nodeCategoryId = textValue(node, "category_id", "categoryId");
            String categoryName = textValue(node, "category_name", "categoryName");
            if (StringUtils.hasText(nodeCategoryId) && StringUtils.hasText(categoryName)) {
                categoryNames.put(nodeCategoryId, categoryName);
            }
            RemoteGoodsItem remote = context.goods().fanchenItem(item.id(), node);
            if (context.goods().matchesKeyword(remote, keyword)) {
                allItems.add(remote);
            }
        }
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(allItems.size(), from + limit);
        List<RemoteGoodsItem> items = from >= allItems.size() ? List.of() : allItems.subList(from, to);
        List<Map<String, Object>> categories = categoryNames.entrySet().stream()
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
            "synced " + items.size() + " fanchen goods from remote total " + allItems.size()
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fanchen baseUrl is required");
        }
        if (!StringUtils.hasText(userId(item))) {
            throw new IllegalArgumentException("fanchen userid is required");
        }
        if (!StringUtils.hasText(key(context))) {
            throw new IllegalArgumentException("fanchen key is required");
        }
    }

    static String userId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private static String key(SupplierCallContext context) {
        return defaultText(context.apiKey(), "").trim();
    }

    private Map<String, Object> baseParams(SupplierCallContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", userId(context.supplier()));
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
        String payload = SupplierHttpClient.formUrlEncoded(body == null ? Map.of() : body, GBK_CHARSET);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action, Set<String> successCodes) {
        String code = textValue(root, "resultno");
        if (!successCodes.contains(code)) {
            String message = textValue(root, "remark1", "msg", "message");
            throw new SupplierBusinessException("fanchen", action, code,
                "fanchen " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private List<String> cards(JsonNode root) {
        JsonNode cardsNode = firstExisting(root, "cards", "cardList", "card_list");
        if (cardsNode == null || !cardsNode.isArray()) {
            return List.of();
        }
        List<String> cards = new ArrayList<>();
        for (JsonNode card : cardsNode) {
            String cardNo = textValue(card, "cardno", "cardNo", "card_no");
            String cardPsw = textValue(card, "cardpsw", "cardPsw", "card_password", "password");
            String effectTime = textValue(card, "effecttime", "effectTime", "expire_time");
            String line = StringUtils.hasText(cardNo)
                ? "卡号：" + cardNo + (StringUtils.hasText(cardPsw) ? " 卡密：" + cardPsw : "")
                : (StringUtils.hasText(cardPsw) ? "卡密：" + cardPsw : "");
            if (StringUtils.hasText(line) && StringUtils.hasText(effectTime)) {
                line += " 有效期：" + effectTime;
            }
            if (StringUtils.hasText(line)) {
                cards.add(line);
            }
        }
        return List.copyOf(cards);
    }

    /**
     * 异步回调报文 -> 归一化快照。字段口径与原 handleFanchenOrderCallback 里
     * 直接 new FanchenOrderStatus(...) 完全一致（回调里 cards 传的是 List.of()）。
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
        return switch (defaultText(upstreamStatus, "").trim()) {
            case "1" -> OrderStatus.DELIVERED;
            case "9" -> OrderStatus.FAILED;
            case "5007" -> fallback == null ? OrderStatus.PROCURING : fallback;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(String status) {
        return switch (defaultText(status, "").trim()) {
            case "0" -> "WAITING";
            case "1" -> "SUCCESS";
            case "2" -> "PROCESSING";
            case "9" -> "FAILED_REFUNDED";
            case "5007" -> "ORDER_NOT_FOUND";
            default -> StringUtils.hasText(status) ? status : "UNKNOWN";
        };
    }

    static String deliveryMessage(String status, String hints) {
        String statusLabel = switch (defaultText(status, "").trim()) {
            case "0", "2" -> "上游充值中";
            case "1" -> "上游充值成功";
            case "9" -> "上游充值失败已退款";
            case "5007" -> "上游暂未查到订单";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
