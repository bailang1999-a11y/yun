package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alipay.api.internal.util.AlipaySignature;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 支付宝异步通知验签测试。
 *
 * <p>没有真实商户密钥也能测：自己生成一对 RSA2048 密钥，用私钥签名充当"支付宝发来的报文"，
 * 把公钥配到通道上。验签算法与真实链路完全一致（都走 {@link AlipaySignature}），
 * 因此这里能真正覆盖<b>安全边界</b>——通知接口是公网匿名可访问的，
 * 报文可信性全靠这一层。
 */
class AlipayGatewayServiceTest {
    private static final AlipayGatewayService SERVICE = new AlipayGatewayService();
    private static final String APP_ID = "2021000000000001";

    private static String privateKey;
    private static String publicKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static PaymentChannelItem channel() {
        return channel(APP_ID);
    }

    private static PaymentChannelItem channel(String appId) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("app_id", appId);
        config.put("app_private_key", privateKey);
        config.put("alipay_public_key", publicKey);
        config.put("notify_url", "https://api.example.com");
        OffsetDateTime now = OffsetDateTime.now();
        return new PaymentChannelItem(
            1L, "alipay", "支付宝", "ALIPAY", List.of("h5", "web"), "ENABLED", 10, config, "", now, now
        );
    }

    /** 构造一份已用私钥正确签名的通知报文。 */
    private static Map<String, String> signedNotify(String tradeStatus, String amount) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", "PAY202607280001");
        params.put("trade_no", "2026072822001400000001");
        params.put("trade_status", tradeStatus);
        params.put("total_amount", amount);
        // 待签名串必须在放入 sign_type 之前算：验签侧 rsaCheckV1 会把 sign 与 sign_type
        // 一并剔除后再拼串，若签名侧把 sign_type 算进去，两边哈希的内容就不一致。
        String signContent = AlipaySignature.getSignContent(params);
        params.put("sign_type", "RSA2");
        params.put("sign", AlipaySignature.rsaSign(signContent, privateKey, "UTF-8", "RSA2"));
        return params;
    }

    @Test
    void acceptsValidlySignedSuccessNotify() throws Exception {
        AlipayGatewayService.NotifyResult result = SERVICE.verifyNotify(channel(), signedNotify("TRADE_SUCCESS", "18.50"));

        assertThat(result.paid()).isTrue();
        assertThat(result.paymentNo()).isEqualTo("PAY202607280001");
        assertThat(result.tradeNo()).isEqualTo("2026072822001400000001");
        assertThat(result.amount()).isEqualByComparingTo("18.50");
    }

    @Test
    void treatsTradeFinishedAsPaid() throws Exception {
        assertThat(SERVICE.verifyNotify(channel(), signedNotify("TRADE_FINISHED", "1.00")).paid()).isTrue();
    }

    /**
     * 中间态不能算已支付。若这里返回 paid=true，用户只要下单打开支付宝页面
     * （产生 WAIT_BUYER_PAY 通知）就能白拿货。
     */
    @Test
    void doesNotTreatWaitBuyerPayAsPaid() throws Exception {
        assertThat(SERVICE.verifyNotify(channel(), signedNotify("WAIT_BUYER_PAY", "18.50")).paid()).isFalse();
    }

    @Test
    void doesNotTreatTradeClosedAsPaid() throws Exception {
        assertThat(SERVICE.verifyNotify(channel(), signedNotify("TRADE_CLOSED", "18.50")).paid()).isFalse();
    }

    /** 篡改金额后签名必然失效——这是"改小金额少付钱"攻击的拦截点。 */
    @Test
    void rejectsTamperedAmount() throws Exception {
        Map<String, String> params = signedNotify("TRADE_SUCCESS", "18.50");
        params.put("total_amount", "0.01");

        assertThatThrownBy(() -> SERVICE.verifyNotify(channel(), params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("支付宝通知验签失败");
    }

    @Test
    void rejectsTamperedOutTradeNo() throws Exception {
        Map<String, String> params = signedNotify("TRADE_SUCCESS", "18.50");
        params.put("out_trade_no", "PAY-SOMEONE-ELSE");

        assertThatThrownBy(() -> SERVICE.verifyNotify(channel(), params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("支付宝通知验签失败");
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        Map<String, String> params = signedNotify("TRADE_SUCCESS", "18.50");
        params.remove("sign");

        assertThatThrownBy(() -> SERVICE.verifyNotify(channel(), params))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验签只证明"报文由某个支付宝商户签发"，不证明"发给本应用"。
     * 少了 app_id 比对，别家商户的合法通知就能拿来顶我方订单。
     */
    @Test
    void rejectsNotifyForAnotherApp() throws Exception {
        Map<String, String> params = signedNotify("TRADE_SUCCESS", "18.50");

        assertThatThrownBy(() -> SERVICE.verifyNotify(channel("2021000000000999"), params))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("支付宝通知 app_id 不匹配");
    }

    @Test
    void rejectsEmptyNotify() {
        assertThatThrownBy(() -> SERVICE.verifyNotify(channel(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("支付宝通知报文为空");
    }

    @Test
    void reportsMissingConfigWithActionableMessage() {
        OffsetDateTime now = OffsetDateTime.now();
        PaymentChannelItem bare = new PaymentChannelItem(
            1L, "alipay", "支付宝", "ALIPAY", List.of("web"), "ENABLED", 10, Map.of(), "", now, now
        );

        assertThatThrownBy(() -> SERVICE.verifyNotify(bare, Map.of("app_id", APP_ID)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("支付宝 AppID");
    }

    @Test
    void supportsOnlyAlipayTypedChannels() {
        OffsetDateTime now = OffsetDateTime.now();
        PaymentChannelItem balance = new PaymentChannelItem(
            2L, "balance", "余额", "BALANCE", List.of("web"), "ENABLED", 1, Map.of(), "", now, now
        );

        assertThat(SERVICE.supports(channel())).isTrue();
        assertThat(SERVICE.supports(balance)).isFalse();
        assertThat(SERVICE.supports(null)).isFalse();
    }

    /** 金额必须是两位小数的元，否则支付宝会拒单。 */
    @Test
    void buildsPaymentUrlWithCanonicalAmount() {
        String url = SERVICE.createPaymentUrl(channel(), "PAY202607280001", new BigDecimal("18.5"), "测试商品", "web");

        assertThat(url).contains("alipay.trade.page.pay");
        assertThat(url).contains("18.50");
    }

    /** 手机端必须走 wap 接口，用 page 接口在手机浏览器里会白屏。 */
    @Test
    void usesWapInterfaceForNonWebTerminal() {
        String url = SERVICE.createPaymentUrl(channel(), "PAY202607280002", new BigDecimal("9.90"), "测试商品", "h5");

        assertThat(url).contains("alipay.trade.wap.pay");
    }

    /** 商品名含引号不能破坏 biz_content 的 JSON 结构。 */
    @Test
    void escapesQuotesInSubject() {
        String url = SERVICE.createPaymentUrl(
            channel(), "PAY202607280003", new BigDecimal("5.00"), "会员\"特惠\"包", "web"
        );

        assertThat(url).isNotBlank();
    }

    @Test
    void rejectsMissingAmountWhenCreatingOrder() {
        assertThatThrownBy(() -> SERVICE.createPaymentUrl(channel(), "PAY202607280004", null, "测试商品", "web"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("支付金额缺失");
    }

    /** 用自定义配置项构造通道，用于覆盖历史脏配置。 */
    private static PaymentChannelItem channelWith(String key, String value) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("app_id", APP_ID);
        config.put("app_private_key", privateKey);
        config.put("alipay_public_key", publicKey);
        config.put("notify_url", "https://api.example.com");
        config.put(key, value);
        OffsetDateTime now = OffsetDateTime.now();
        return new PaymentChannelItem(
            1L, "alipay", "支付宝", "ALIPAY", List.of("h5", "web"), "ENABLED", 10, config, "", now, now
        );
    }

    /**
     * 历史脏配置回归：库里存的 notify_url 可能是旧版本写入的「域名+路径」。
     * 若直接在其后追加路径，会拼出 .../api/callback/payments/alipay/api/payment/notify/alipay，
     * 支付宝将通知投递到不存在的地址，订单永远收不到回调。
     */
    @Test
    void ignoresStalePathInStoredNotifyUrl() {
        String url = SERVICE.createPaymentUrl(
            channelWith("notify_url", "https://api.xiyi.co/api/callback/payments/alipay"),
            "PAY202607280005", new BigDecimal("0.02"), "测试商品", "web"
        );

        assertThat(url).contains(encode("https://api.xiyi.co/api/payment/notify/alipay"));
        assertThat(url).doesNotContain(encode("/api/callback/payments/alipay"));
    }

    @Test
    void keepsPortWhenDerivingNotifyUrl() {
        String url = SERVICE.createPaymentUrl(
            channelWith("notify_url", "https://api.xiyi.co:8443/whatever/path"),
            "PAY202607280006", new BigDecimal("0.02"), "测试商品", "web"
        );

        assertThat(url).contains(encode("https://api.xiyi.co:8443/api/payment/notify/alipay"));
    }

    /**
     * 把公钥误粘进「应用私钥」是最容易犯的配置错误（两者都是 base64，肉眼难分）。
     * 必须在下单前拦住并说清是哪一栏填错，否则只会抛出 SDK 的
     * "RSA2签名遭遇异常" 堆栈，运营无法自助定位。
     */
    @Test
    void rejectsPublicKeyPastedIntoPrivateKeyField() {
        assertThatThrownBy(() -> SERVICE.createPaymentUrl(
            channelWith("app_private_key", publicKey),
            "PAY202607280007", new BigDecimal("0.02"), "测试商品", "web"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("应用私钥");
    }

    /** 带 PEM 头尾与换行的私钥应被接受（运营常直接粘 .pem 文件全文）。 */
    @Test
    void acceptsPemWrappedPrivateKey() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + privateKey.replaceAll("(.{64})", "$1\n")
            + "\n-----END PRIVATE KEY-----";

        String url = SERVICE.createPaymentUrl(
            channelWith("app_private_key", pem),
            "PAY202607280008", new BigDecimal("0.02"), "测试商品", "web"
        );

        assertThat(url).isNotBlank();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
