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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 蜂助手适配器。签名沿用 {@link FengzhushouSignatureUtil}，拼接方式零改动。
 *
 * <p><b>能力缺口</b>：蜂助手不提供上游商品列表，{@link #supportsRemoteGoodsSync()} 为 false。</p>
 */
@Component
public class FengzhushouSupplierAdapter implements SupplierAdapter {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("fengzhushou");

    @Override
    public SupplierPlatform platform() {
        return SupplierPlatform.FENGZHUSHOU;
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
        body.put("sign", FengzhushouSignatureUtil.sign(body, signKey(context)));
        JsonNode root = postJson(context, "/fzs-stdopen-api/api/v1/balance", body, "balance refresh", true);
        ensureOk(root, "balance refresh");
        return context.supplier().withBalance(balance(root)).withLastSyncAt(OffsetDateTime.now());
    }

    @Override
    public UpstreamSubmitResult submitOrder(
        SupplierCallContext context,
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price
    ) {
        SupplierItem supplier = context.supplier();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", projectCode(supplier));
        body.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        body.put("skuCode", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("channelOrderNo", order.orderNo());
        body.put("account", defaultText(order.rechargeAccount(), "").trim());
        body.put("num", order.quantity() == null ? 1 : order.quantity());
        body.put("callbackUrl", context.callbackUrl());
        body.put("skuPrice", price.totalCost());
        body.put("ext", defaultText(order.buyerRemark(), "").trim());
        body.put("sign", FengzhushouSignatureUtil.sign(body, signKey(context)));

        JsonNode root = postJson(context, "/fzs-stdopen-api/api/v1/sendgoods", body, "order submit", false);
        int code = intValue(root.path("retcode"), -1);
        if (isPublicError(code)) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("fengzhushou", "order submit", String.valueOf(code),
                "fengzhushou order submit failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
        String prefix = code == 9999 || code != 0 ? "上游返回处理中，已提交待查单" : "上游已收单，等待处理";
        return new UpstreamSubmitResult(
            List.of("外部订单号：" + order.orderNo()),
            prefix + "，外部订单号：" + order.orderNo(),
            firstText(textValue(root.path("data"), "orderNo", "order_no", "orderId"), textValue(root, "orderNo", "order_no"), "")
        );
    }

    @Override
    public UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback) {
        Map<String, Object> body = baseParams(context);
        body.put("channelOrderNo", order.orderNo());
        body.put("sign", FengzhushouSignatureUtil.sign(body, signKey(context)));
        JsonNode root = postJson(context, "/fzs-stdopen-api/api/v1/queryorder", body, "order info sync", true);
        int code = intValue(root.path("retcode"), -1);
        if (code == 9999 || code == 3000) {
            String hints = firstText(textValue(root, "msg", "message"), "上游暂未返回最终状态", "");
            return new UpstreamOrderSnapshot(
                "",
                order.orderNo(),
                localStatus(code, fallback),
                statusLabel(code),
                deliveryMessage(code, hints),
                hints,
                null,
                "",
                List.of(),
                abbreviate(root.toString(), 1200)
            );
        }
        JsonNode data = root.path("data");
        String hints = textValue(root, "msg", "message");
        return new UpstreamOrderSnapshot(
            firstText(textValue(data, "orderNo", "order_no", "orderId"), textValue(root, "orderNo", "order_no"), ""),
            order.orderNo(),
            localStatus(code, fallback),
            statusLabel(code),
            deliveryMessage(code, hints),
            hints,
            optionalDecimalValue(data, "skuPrice", "price", "amount", "totalPrice"),
            "",
            List.of(),
            abbreviate(root.toString(), 1200)
        );
    }

    // ---------------------------------------------------------------- 协议细节

    private void validateCredentials(SupplierCallContext context) {
        SupplierItem item = context.supplier();
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fengzhushou baseUrl is required");
        }
        if (!StringUtils.hasText(projectCode(item))) {
            throw new IllegalArgumentException("fengzhushou projectCode is required");
        }
        if (!StringUtils.hasText(signKey(context))) {
            throw new IllegalArgumentException("fengzhushou signKey is required");
        }
    }

    static String projectCode(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private static String signKey(SupplierCallContext context) {
        return defaultText(context.apiKey(), "").trim();
    }

    private Map<String, Object> baseParams(SupplierCallContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", projectCode(context.supplier()));
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
            throw new IllegalStateException("fengzhushou " + action + " failed: invalid JSON payload or response");
        }
        URI uri = URI.create(trimTrailingSlash(context.baseUrl()) + path);
        SupplierHttpRequest request = idempotent
            ? SupplierHttpRequest.query(uri, payload, context.timeout(), action)
            : SupplierHttpRequest.mutation(uri, payload, context.timeout(), action);
        return context.http().postJson(PROFILE, request);
    }

    private void ensureOk(JsonNode root, String action) {
        String code = textValue(root, "retcode", "code");
        if (!Set.of("0", "0000").contains(defaultText(code, "").trim())) {
            String message = textValue(root, "msg", "message", "error");
            throw new SupplierBusinessException("fengzhushou", action, code,
                "fengzhushou " + action + " failed: code=" + code
                    + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private BigDecimal balance(JsonNode root) {
        BigDecimal balance = optionalDecimalValue(root, "balance", "amount", "money", "availableBalance", "available_balance");
        if (balance != null) {
            return balance;
        }
        JsonNode data = root.path("data");
        balance = optionalDecimalValue(data, "balance", "amount", "money", "availableBalance", "available_balance");
        if (balance != null) {
            return balance;
        }
        JsonNode account = firstExisting(data, "account", "wallet", "merchant", "balanceInfo", "balance_info");
        if (account != null) {
            balance = optionalDecimalValue(account, "balance", "amount", "money", "availableBalance", "available_balance");
            if (balance != null) {
                return balance;
            }
        }
        throw new IllegalStateException("fengzhushou balance refresh failed: balance field is missing");
    }

    static boolean isPublicError(int code) {
        return code == 102 || code == 103 || code == 107 || code == 400 || code == 1000;
    }

    /**
     * 异步回调报文 -> 归一化快照。字段口径与原 handleFengzhushouOrderCallback 里
     * 直接 new FengzhushouOrderStatus(...) 完全一致（蜂助手回调不带卡密与金额）。
     */
    static UpstreamOrderSnapshot callbackSnapshot(
        String upstreamOrderNo,
        String externalOrderNo,
        int status,
        String hints,
        String rawResponse
    ) {
        return new UpstreamOrderSnapshot(
            upstreamOrderNo,
            externalOrderNo,
            localStatus(status, null),
            statusLabel(status),
            deliveryMessage(status, hints),
            hints,
            null,
            "",
            List.of(),
            rawResponse
        );
    }

    static OrderStatus localStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 1 -> OrderStatus.DELIVERED;
            case 9, 2000, 3100, 3104, 3999, 4001, 4002 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    static String statusLabel(int status) {
        return switch (status) {
            case 0 -> "PROCESSING";
            case 1 -> "SUCCESS";
            case 9 -> "FAIL";
            case 2000 -> "SKU_OFFLINE";
            case 3000 -> "ORDER_NOT_FOUND";
            case 3100 -> "ACCOUNT_RESTRICTED";
            case 3104 -> "PRICE_MISMATCH";
            case 3999 -> "ORDER_CREATE_FAILED";
            case 4001 -> "DEDUCT_FAILED";
            case 4002 -> "BALANCE_NOT_ENOUGH";
            case 9999 -> "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    static String deliveryMessage(int status, String hints) {
        String statusLabel = switch (status) {
            case 0 -> "上游发货中";
            case 1 -> "上游发货成功";
            case 9 -> "上游发货失败";
            case 2000 -> "上游单品不存在或已下架";
            case 3000 -> "上游暂未查到订单";
            case 3100 -> "充值账号无法购买此商品";
            case 3104 -> "上游价格不匹配";
            case 3999 -> "上游订单生成失败";
            case 4001 -> "上游扣款失败";
            case 4002 -> "上游账户余额不足";
            case 9999 -> "上游系统内部错误，状态待确认";
            default -> "上游状态未知";
        };
        return statusLabel + (StringUtils.hasText(hints) ? "：" + hints : "");
    }
}
