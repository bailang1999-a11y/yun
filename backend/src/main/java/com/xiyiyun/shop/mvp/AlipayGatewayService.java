package com.xiyiyun.shop.mvp;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 支付宝网关服务：下单取支付地址 + 异步通知 RSA2 验签。
 *
 * <p>为什么单独一个类：原有 {@link PaymentCallbackSignatureVerifier} 是自定义
 * HMAC-SHA256 报文（我方与上游约定的 v1/v2 规格），而支付宝异步通知是
 * <b>表单参数 + RSA2 签名</b>，两套验签算法完全不同，不能混用。
 *
 * <p>密钥来源：{@link PaymentChannelItem#config()}，即后台「支付通道管理」里录入的
 * {@code app_id} / {@code app_private_key} / {@code alipay_public_key}，
 * 不落配置文件，也不随 {@code publicPaymentChannel} 下发到前端。
 */
@Component
public class AlipayGatewayService {
    private static final Logger log = LoggerFactory.getLogger(AlipayGatewayService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String SIGN_TYPE = "RSA2";
    private static final String CHARSET = "UTF-8";
    private static final String FORMAT = "json";

    /** 通知里代表"钱已到账"的两个终态，其余状态（WAIT_BUYER_PAY / TRADE_CLOSED）不算成功。 */
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String TRADE_FINISHED = "TRADE_FINISHED";

    public boolean supports(PaymentChannelItem channel) {
        return channel != null && "ALIPAY".equalsIgnoreCase(text(channel.type()));
    }

    /**
     * 通道配置快照。{@code notifyBase} 是后台填的后端 API 域名，
     * 完整通知地址由 {@link #notifyUrl()} 拼出，避免运营手抄路径抄错。
     */
    private record AlipayConfig(
        String appId,
        String privateKey,
        String alipayPublicKey,
        String gateway,
        String notifyBase,
        String returnUrl
    ) {
        /**
         * 通知地址 = 域名 + {@link #NOTIFY_PATH}。
         *
         * <p>这里<b>只取 scheme + host</b> 再拼路径，而不是直接在配置值后面追加。
         * 原因：历史版本的后台把「域名 + 路径」整体存进了这个字段，
         * 若直接追加会拼出 {@code https://host/api/callback/payments/alipay/api/payment/notify/alipay}
         * 这种双路径地址，支付宝通知会打到不存在的路由上，导致订单永远不会转为已支付。
         * 取 host 重拼可让新旧存量数据都得到正确结果。
         */
        String notifyUrl() {
            return hostOnly(notifyBase) + NOTIFY_PATH;
        }
    }

    /** 异步通知路径。后台配置里只填域名，这个后缀由后端拼，前后端必须一致。 */
    public static final String NOTIFY_PATH = "/api/payment/notify/alipay";

    private AlipayConfig config(PaymentChannelItem channel) {
        Map<String, String> raw = channel.config() == null ? Map.of() : channel.config();
        String appId = text(raw.get("app_id"));
        String privateKey = text(raw.get("app_private_key"));
        String publicKey = text(raw.get("alipay_public_key"));
        boolean sandbox = "true".equalsIgnoreCase(text(raw.get("sandbox")));
        String gateway = text(raw.get("gateway_url"));
        if (!StringUtils.hasText(gateway)) {
            gateway = sandbox ? SANDBOX_GATEWAY : DEFAULT_GATEWAY;
        }

        // 缺任一密钥都不可能下单成功，这里直接给出可定位的中文提示，
        // 而不是让 SDK 抛一个运营看不懂的 AlipayApiException。
        requireConfig(appId, "支付宝 AppID");
        requireConfig(privateKey, "应用私钥");
        requireConfig(publicKey, "支付宝公钥");
        String notifyBase = text(raw.get("notify_url"));
        requireConfig(notifyBase, "支付回调地址（后端 API 域名）");

        // 运营常常直接粘贴 .pem 文件全文（带 BEGIN/END 行与换行），
        // 而 SDK 只接受纯 base64；这里统一剥掉，省得因为多两行头尾就签名失败。
        privateKey = stripPemArmour(privateKey);
        publicKey = stripPemArmour(publicKey);
        requirePrivateKeyShape(privateKey);

        return new AlipayConfig(appId, privateKey, publicKey, gateway, notifyBase, text(raw.get("return_url")));
    }

    private void requireConfig(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("支付宝通道未配置" + label + "，请在后台支付通道管理中补全");
        }
    }

    /**
     * 私钥形状预检：把"粘错内容"这类配置错误在下单前拦下来。
     *
     * <p>动机来自一次真实故障：运营把<b>支付宝公钥</b>粘进了「应用私钥」框，
     * SDK 抛出的是 {@code DerValue.getBigIntegerInternal, not expected 48} 这种
     * 完全无法定位的底层异常，排查要靠反查库里的字段长度才发现。
     *
     * <p>判据是长度：2048 位 PKCS8 私钥的 base64 约 1600 字符，
     * 而 RSA 公钥只有约 392 字符。公钥特有的 {@code MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A}
     * 前缀则可以直接指认"这是公钥"。
     */
    private void requirePrivateKeyShape(String privateKey) {
        String compact = privateKey.replaceAll("\\s+", "");
        if (compact.startsWith("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A")) {
            throw new IllegalStateException(
                "「应用私钥」里填的是公钥。请填支付宝开放平台生成的<应用私钥>"
                    + "（PKCS8 格式、约 1600 字符），不要填公钥。"
            );
        }
        if (compact.length() < 1000) {
            throw new IllegalStateException(
                "「应用私钥」格式不正确（当前长度 " + compact.length()
                    + " 字符，2048 位私钥应约 1600 字符）。请粘贴完整的应用私钥内容，"
                    + "不要包含 -----BEGIN/END----- 行。"
            );
        }
    }

    /**
     * 向支付宝下单，返回让用户跳转去付款的地址。
     *
     * <p>{@code paymentNo} 作为 {@code out_trade_no} 传给支付宝，异步通知回来时
     * 用它直接定位到我方的 payment 行，因此<b>不能复用</b>（同一订单重新支付会生成新的 paymentNo）。
     *
     * @param terminal {@code web} 走电脑网站支付，其余走手机网站支付
     */
    public String createPaymentUrl(
        PaymentChannelItem channel,
        String paymentNo,
        BigDecimal amount,
        String subject,
        String terminal
    ) {
        AlipayConfig config = config(channel);
        AlipayClient client = new DefaultAlipayClient(
            config.gateway(),
            config.appId(),
            config.privateKey(),
            FORMAT,
            CHARSET,
            config.alipayPublicKey(),
            SIGN_TYPE
        );

        boolean web = "web".equalsIgnoreCase(text(terminal));
        String bizContent = bizContent(paymentNo, amount, subject, web);

        try {
            if (web) {
                AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
                request.setNotifyUrl(config.notifyUrl());
                if (StringUtils.hasText(config.returnUrl())) {
                    request.setReturnUrl(config.returnUrl());
                }
                request.setBizContent(bizContent);
                // GET 方式返回可直接跳转的 URL，而不是一段需要前端注入的自动提交表单。
                return client.pageExecute(request, "GET").getBody();
            }
            AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
            request.setNotifyUrl(config.notifyUrl());
            if (StringUtils.hasText(config.returnUrl())) {
                request.setReturnUrl(config.returnUrl());
            }
            request.setBizContent(bizContent);
            return client.pageExecute(request, "GET").getBody();
        } catch (AlipayApiException ex) {
            // 不把 SDK 原始异常抛给前端：可能含 AppID 等信息。日志里留全量便于排查。
            log.error("Alipay create order failed for paymentNo={}", paymentNo, ex);
            throw new IllegalStateException("支付宝下单失败：" + text(ex.getErrMsg()));
        }
    }

    /** biz_content 用 Jackson 序列化：商品名可能含引号，手拼字符串会破坏 JSON。 */
    private String bizContent(String paymentNo, BigDecimal amount, String subject, boolean web) {
        Map<String, String> content = new LinkedHashMap<>();
        content.put("out_trade_no", paymentNo);
        content.put("total_amount", canonicalAmount(amount));
        content.put("subject", StringUtils.hasText(subject) ? subject : "订单 " + paymentNo);
        content.put("product_code", web ? "FAST_INSTANT_TRADE_PAY" : "QUICK_WAP_WAY");
        try {
            return OBJECT_MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("支付宝下单参数序列化失败", ex);
        }
    }

    /** 支付宝要求金额为元、两位小数。 */
    private String canonicalAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalStateException("支付金额缺失，无法向支付宝下单");
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 验签通过后的通知要素。 */
    public record NotifyResult(
        String paymentNo,
        String tradeNo,
        BigDecimal amount,
        boolean paid
    ) {}

    /**
     * 校验支付宝异步通知。
     *
     * <p>这个方法是整条链路的信任边界：通知接口本身是公网可匿名访问的，
     * 报文可信<b>完全</b>取决于这里的 RSA2 验签。三道校验缺一不可：
     * <ol>
     *   <li><b>RSA2 验签</b>：证明报文确实由支付宝用其私钥签发，未被篡改；</li>
     *   <li><b>app_id 比对</b>：验签只证明"是支付宝发的"，不证明"是发给本应用的"。
     *       不比对 app_id，则任何一个支付宝商户的合法通知都能拿来顶我方订单；</li>
     *   <li><b>trade_status 白名单</b>：只有 TRADE_SUCCESS / TRADE_FINISHED 算收到钱，
     *       WAIT_BUYER_PAY 与 TRADE_CLOSED 绝不能置为已支付。</li>
     * </ol>
     * 金额一致性不在这里判，交给下游 {@code ensurePaymentCallbackAmountMatches}
     * 与库里 payment.amount 比对，避免两处各写一套判断而产生分歧。
     */
    public NotifyResult verifyNotify(PaymentChannelItem channel, Map<String, String> params) {
        AlipayConfig config = config(channel);
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("支付宝通知报文为空");
        }

        boolean signed;
        try {
            signed = AlipaySignature.rsaCheckV1(new HashMap<>(params), config.alipayPublicKey(), CHARSET, SIGN_TYPE);
        } catch (AlipayApiException ex) {
            log.warn("Alipay notify signature check error", ex);
            throw new IllegalArgumentException("支付宝通知验签异常");
        }
        if (!signed) {
            throw new IllegalArgumentException("支付宝通知验签失败");
        }

        String notifyAppId = text(params.get("app_id"));
        if (!notifyAppId.equals(config.appId())) {
            log.warn("Alipay notify app_id mismatch: notify={} configured={}", notifyAppId, config.appId());
            throw new IllegalArgumentException("支付宝通知 app_id 不匹配");
        }

        String paymentNo = text(params.get("out_trade_no"));
        if (!StringUtils.hasText(paymentNo)) {
            throw new IllegalArgumentException("支付宝通知缺少 out_trade_no");
        }

        String tradeStatus = text(params.get("trade_status"));
        boolean paid = TRADE_SUCCESS.equals(tradeStatus) || TRADE_FINISHED.equals(tradeStatus);

        BigDecimal amount = null;
        String rawAmount = text(params.get("total_amount"));
        if (StringUtils.hasText(rawAmount)) {
            try {
                amount = new BigDecimal(rawAmount);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("支付宝通知金额格式非法");
            }
        }

        return new NotifyResult(paymentNo, text(params.get("trade_no")), amount, paid);
    }

    /** 去掉 PEM 头尾行与所有空白，只留 base64 本体。 */
    private static String stripPemArmour(String value) {
        return text(value)
            .replaceAll("-----BEGIN[^-]*-----", "")
            .replaceAll("-----END[^-]*-----", "")
            .replaceAll("\\s+", "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = text(value);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 从配置值里只取 {@code scheme://host[:port]}，丢弃已有路径。
     *
     * <p>用于容忍历史数据把整条回调地址存进「域名」字段的情况。
     * 解析失败时退回去尾斜杠的原值，不让一个格式问题阻断下单。
     */
    private static String hostOnly(String value) {
        String trimmed = trimTrailingSlash(value);
        if (!StringUtils.hasText(trimmed)) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return trimmed;
            }
            String base = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() > 0 ? base + ":" + uri.getPort() : base;
        } catch (IllegalArgumentException ex) {
            return trimmed;
        }
    }
}
