package com.xiyiyun.shop.mvp;

import static com.xiyiyun.shop.mvp.SupplierJson.abbreviate;
import static com.xiyiyun.shop.mvp.SupplierJson.defaultText;
import static com.xiyiyun.shop.mvp.SupplierJson.firstExisting;
import static com.xiyiyun.shop.mvp.SupplierJson.firstText;
import static com.xiyiyun.shop.mvp.SupplierJson.intValue;
import static com.xiyiyun.shop.mvp.SupplierJson.optionalDecimalValue;
import static com.xiyiyun.shop.mvp.SupplierJson.textValue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 福禄新平台适配器。签名沿用 {@link FuluSignatureUtil}，请求/响应签名拼接方式零改动。
 *
 * <p><b>能力缺口</b>：福禄不提供上游商品列表接口，{@link #supportsRemoteGoodsSync()} 为 false，
 * {@link #fetchRemoteGoods} 保持接口默认抛不支持。</p>
 */
@Component
public class FuluSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("fulu");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.FULU;
    }

    @Override
    public boolean supportsRemoteGoodsSync() {
        return false;
    }

    @Override
    public boolean supportsSingleGoodsQuery() {
        return false;
    }

    @Override
    public String sourceConnectUnsupportedHint() {
        return "福禄新平台不支持获取上游商品，请手动填写 product_id 创建商品对接";
    }

    @Override
    public SupplierItem testConnection(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }
        JsonNode root = postJson(context, "merchant.balance.query", Map.of(), "test connection", true);
        ensureOk(root, "test connection");
        verifyResponseSign(root, context, "test connection");
        return context.supplier().withBalance(balance(root)).withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public SupplierItem refreshBalance(SupplierCallContext context) {
        validateCredentials(context);
        if (SupplierBaseUrl.isPlaceholder(context.supplier().baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        JsonNode root = postJson(context, "merchant.balance.query", Map.of(), "balance refresh", true);
        ensureOk(root, "balance refresh");
        verifyResponseSign(root, context, "balance refresh");
        return context.supplier().withBalance(balance(root));
    }

    @Override
    public UpstreamSubmitResult submitOrder(
        SupplierCallContext context,
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price
    ) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        biz.put("customer_order_no", order.orderNo());
        if (StringUtils.hasText(order.rechargeAccount())) {
            biz.put("charge_account", order.rechargeAccount().trim());
        }
        biz.put("buy_num", order.quantity() == null ? 1 : order.quantity());
        if (StringUtils.hasText(order.orderIp())) {
            biz.put("charge_ip", order.orderIp().trim());
        }
        biz.put("customer_price", price.totalCost());

        JsonNode root = postJson(context, "order.notify", biz, "order submit", false);
        ensureOk(root, "order submit");
        verifyResponseSign(root, context, "order submit");
        UpstreamOrderSnapshot upstream = snapshotFromResult(root.path("result").asText(""), null);
        List<String> deliveryItems = upstream.mergedDeliveryItems(List.of());
        String priceText = upstream.totalPrice() == null ? "" : "，上游金额：" + upstream.totalPrice();
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstream.upstreamOrderNo()) ? "，上游订单号：" + upstream.upstreamOrderNo() : "")
            + priceText;
        return new UpstreamSubmitResult(List.copyOf(deliveryItems), attemptMessage, upstream.upstreamOrderNo());
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("customer_order_no", order.orderNo());
        JsonNode root = postJson(context, "order.query", biz, "order info sync", true);
        int code = intValue(root.path("code"), -1);
        if (code == 4011 || code == 5000) {
            String message = firstText(textValue(root, "msg", "message", "sub_msg"), "上游暂未返回最终状态", "");
            return new UpstreamOrderSnapshot(
                "",
                order.orderNo(),
                localStatus(2, fallback),
                statusLabel(2),
                deliveryMessage(2, message),
                message,
                null,
                "",
                List.of(),
                abbreviate(root.toString(), 1200)
            );
        }
        ensureOk(root, "order info sync");
        verifyResponseSign(root, context, "order info sync");
        return snapshotFromResult(root.path("result").asText(""), fallback);
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fulu baseUrl is required");
        }
        if (!StringUtils.hasText(appKey(item))) {
            throw new IllegalArgumentException("fulu app_key is required");
        }
        if (!StringUtils.hasText(appSecret(context))) {
            throw new IllegalArgumentException("fulu app_secret is required");
        }
    }

    static String appKey(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private static String appSecret(SupplierCallContext context) {
        return defaultText(context.apiKey(), "").trim();
    }

    private JsonNode postJson(
        SupplierCallContext context,
        String method,
        Map<String, Object> bizObject,
        String action,
        boolean idempotent
    ) {
        validateCredentials(context);
        SupplierItem item = context.supplier();
        Map<String, Object> biz = new LinkedHashMap<>();
        if (bizObject != null) {
            biz.putAll(bizObject);
        }
        String payload;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("app_key", appKey(item));
            body.put("method", method);
            body.put("timestamp", TIMESTAMP_FORMAT.format(ZonedDateTime.now(CHINA_ZONE)));
            body.put("version", "1.0");
            body.put("format", "json");
            body.put("charset", "utf-8");
            body.put("sign_type", "md5");
            body.put("biz_content", SupplierJson.MAPPER.writeValueAsString(biz));
            body.put("sign", FuluSignatureUtil.requestSign(body, appSecret(context)));
            payload = SupplierJson.MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fulu " + action + " failed: invalid JSON payload or response");
        }
        URI uri = URI.create(item.baseUrl().trim());
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 200) {
            String message = textValue(root, "msg", "message", "sub_msg", "error");
            throw new SupplierBusinessException("fulu", action, String.valueOf(code),
                "fulu " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private void verifyResponseSign(JsonNode root, SupplierCallContext context, String action) {
        String sign = textValue(root, "sign");
        String result = root.path("result").asText("");
        if (!StringUtils.hasText(sign) || !StringUtils.hasText(result)) {
            return;
        }
        String expected = FuluSignatureUtil.responseSign(result, appSecret(context));
        if (!Objects.equals(sign.trim().toLowerCase(Locale.ROOT), expected)) {
            // A4：签名校验不过意味着我方无法确认这份响应，结果未知（不是上游明确拒单）。
            throw new SupplierTransportException("fulu", action, "fulu " + action + " failed: invalid response sign");
        }
    }

    private BigDecimal balance(JsonNode root) {
        String result = root.path("result").asText("");
        if (!StringUtils.hasText(result)) {
            throw new IllegalStateException("fulu balance refresh failed: result is empty");
        }
        try {
            JsonNode node = SupplierJson.MAPPER.readTree(result);
            JsonNode balances = firstExisting(node, "Balances", "balances", "BalanceList", "balanceList");
            BigDecimal fallback = optionalDecimalValue(node, "Balance", "balance", "amount");
            if (balances != null && balances.isArray()) {
                for (JsonNode item : balances) {
                    int accountType = intValue(firstExisting(item, "AccountType", "accountType"), -1);
                    BigDecimal balance = optionalDecimalValue(item, "Balance", "balance");
                    if (accountType == 1 && balance != null) {
                        return balance;
                    }
                    if (fallback == null && balance != null) {
                        fallback = balance;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fulu balance refresh failed: invalid result JSON");
        }
        throw new IllegalStateException("fulu balance refresh failed: balance field is missing");
    }

    /**
     * 回调报文 biz_content → 快照。对应原 {@code handleFuluOrderCallback} 里
     * 「先按 result 解析，失败再退化成扁平字段」的两段式，语义保持不变。
     */
    static UpstreamOrderSnapshot callbackSnapshot(String bizContent, OrderStatus fallback) {
        try {
            return snapshotFromResult(bizContent, fallback);
        } catch (RuntimeException ex) {
            try {
                JsonNode node = SupplierJson.MAPPER.readTree(bizContent);
                int status = intValue(firstExisting(node, "order_status", "orderStatus", "status"), 0);
                String hints = textValue(node, "charge_remark", "chargeRemark", "message", "msg");
                return new UpstreamOrderSnapshot(
                    textValue(node, "order_id", "orderId"),
                    textValue(node, "customer_order_no", "customerOrderNo"),
                    localStatus(status, fallback),
                    statusLabel(status),
                    deliveryMessage(status, hints),
                    hints,
                    optionalDecimalValue(node, "total_price", "totalPrice", "customer_price", "customerPrice"),
                    textValue(node, "product_name", "productName"),
                    List.of(),
                    abbreviate(node.toString(), 1200)
                );
            } catch (JsonProcessingException jsonEx) {
                throw ex;
            }
        }
    }

    private static UpstreamOrderSnapshot snapshotFromResult(String result, OrderStatus fallback) {
        if (!StringUtils.hasText(result)) {
            throw new SupplierTransportException("fulu", "order info sync", "fulu order info sync failed: result is empty");
        }
        try {
            JsonNode node = SupplierJson.MAPPER.readTree(result);
            List<String> cards = new ArrayList<>();
            JsonNode cardList = firstExisting(node, "card_pwds", "cardPwds", "cards", "card_list");
            if (cardList != null && cardList.isArray()) {
                for (JsonNode card : cardList) {
                    String cardNo = textValue(card, "card_no", "cardNo", "card");
                    String cardPwd = textValue(card, "card_pwd", "cardPwd", "card_password", "password");
                    String expireTime = textValue(card, "expire_time", "expireTime");
                    String line = StringUtils.hasText(cardNo)
                        ? "卡号：" + cardNo + (StringUtils.hasText(cardPwd) ? " 卡密：" + cardPwd : "")
                        : (StringUtils.hasText(cardPwd) ? "卡密：" + cardPwd : "");
                    if (StringUtils.hasText(line) && StringUtils.hasText(expireTime)) {
                        line = line + " 有效期：" + expireTime;
                    }
                    if (StringUtils.hasText(line)) {
                        cards.add(line);
                    }
                }
            }
            List<String> hintParts = new ArrayList<>();
            for (String value : List.of(
                textValue(node, "charge_remark", "chargeRemark", "message", "msg"),
                textValue(node, "inner_charge_remark", "innerChargeRemark"),
                textValue(node, "express_name", "expressName"),
                textValue(node, "express_no", "expressNo")
            )) {
                if (StringUtils.hasText(value)) {
                    hintParts.add(value);
                }
            }
            String hints = String.join("；", hintParts);
            int status = intValue(firstExisting(node, "order_status", "orderStatus", "status"), 0);
            return new UpstreamOrderSnapshot(
                textValue(node, "order_id", "orderId", "order_no", "orderNo"),
                textValue(node, "customer_order_no", "customerOrderNo"),
                localStatus(status, fallback),
                statusLabel(status),
                deliveryMessage(status, hints),
                hints,
                optionalDecimalValue(node, "total_price", "totalPrice", "customer_price", "customerPrice"),
                textValue(node, "product_name", "productName"),
                List.copyOf(cards),
                abbreviate(node.toString(), 1200)
            );
        } catch (JsonProcessingException ex) {
            throw new SupplierTransportException("fulu", "order info sync",
                "fulu order info sync failed: invalid result JSON", null, ex);
        }
    }

    static OrderStatus localStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 3 -> OrderStatus.DELIVERED;
            case 4 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "UNTREATED";
            case 2 -> "PROCESSING";
            case 3 -> "SUCCESS";
            case 4 -> "FAIL";
            default -> "UNKNOWN";
        };
    }

    static String deliveryMessage(int status, String hints) {
        String statusLabel = switch (status) {
            case 1 -> "上游未处理";
            case 2 -> "上游充值中";
            case 3 -> "上游充值成功";
            case 4 -> "上游充值失败";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
