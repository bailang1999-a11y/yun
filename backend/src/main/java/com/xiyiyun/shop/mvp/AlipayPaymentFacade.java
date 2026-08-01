package com.xiyiyun.shop.mvp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付宝支付编排：下单取跳转地址、异步通知落账。
 *
 * <p>放在独立类而不是塞进 {@link InMemoryShopRepository} 的原因：仓储有三个构造重载，
 * 往构造函数里再加一个依赖会牵连既有测试；而这里只需要组合仓储已有的公开能力，
 * 不需要触碰仓储内部状态。
 *
 * <p>落账刻意<b>不自己写一套</b>，而是复用 {@link InMemoryShopRepository#handlePaymentCallback}：
 * 那条路径已经包含金额比对、幂等、状态机校验与资金流水，
 * 再写第二套等于给资金逻辑开第二个分叉，迟早不一致。
 */
@Service
public class AlipayPaymentFacade {
    private static final Logger log = LoggerFactory.getLogger(AlipayPaymentFacade.class);

    private final InMemoryShopRepository repository;
    private final AlipayGatewayService alipayGatewayService;

    public AlipayPaymentFacade(InMemoryShopRepository repository, AlipayGatewayService alipayGatewayService) {
        this.repository = repository;
        this.alipayGatewayService = alipayGatewayService;
    }

    /** 下单结果：前端拿 {@code payUrl} 跳转，拿 {@code paymentNo} 轮询支付结果。 */
    public record PrepareResult(String paymentNo, String payUrl) {}

    /**
     * 向支付宝下单。
     *
     * <p>顺序很重要：先建 PENDING 支付单拿到 {@code paymentNo}，再拿它当
     * {@code out_trade_no} 去支付宝下单。这样通知回来时一定能在库里找到对应支付单。
     *
     * <p>若下单失败，库里会残留一条 PENDING 支付单。这是可接受的：PENDING 不涉及任何资金，
     * 也不会让订单变成已支付，订单超时关闭逻辑照常生效。
     */
    public PrepareResult prepare(String orderNo, Long userId, String payMethod, String terminal) {
        OrderItem order = repository.findOrderForUser(orderNo, userId)
            .orElseThrow(() -> new IllegalArgumentException("order not found"));

        PaymentChannelItem channel = repository.resolvePaymentChannel(payMethod, terminal);
        if (!alipayGatewayService.supports(channel)) {
            throw new IllegalStateException("该支付通道不是支付宝类型，无法发起支付宝支付");
        }

        PaymentItem payment = repository.createPendingPayment(orderNo, userId, payMethod, terminal);
        String payUrl = alipayGatewayService.createPaymentUrl(
            channel,
            payment.paymentNo(),
            payment.amount(),
            order.goodsName(),
            terminal
        );
        return new PrepareResult(payment.paymentNo(), payUrl);
    }

    /**
     * 处理支付宝异步通知。
     *
     * @return 支付宝要求的应答体：成功返回 {@code success}，其余一律 {@code failure}（会触发重投）
     */
    public String handleNotify(Map<String, String> params) {
        String appId = params == null ? "" : params.getOrDefault("app_id", "");
        PaymentChannelItem channel = repository.findAlipayChannelByAppId(appId).orElse(null);
        if (channel == null) {
            log.warn("Alipay notify rejected: no ALIPAY channel matches app_id={}", appId);
            return "failure";
        }

        AlipayGatewayService.NotifyResult result;
        try {
            result = alipayGatewayService.verifyNotify(channel, params);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 验签失败时把关键参数写进日志，方便排查公钥配置错误。
            // out_trade_no 对应我方 payment_no，是最关键的恢复字段。
            log.warn("Alipay notify verification failed: {} | app_id={} out_trade_no={} trade_status={} sign_type={}",
                ex.getMessage(),
                params.getOrDefault("app_id", ""),
                params.getOrDefault("out_trade_no", ""),
                params.getOrDefault("trade_status", ""),
                params.getOrDefault("sign_type", "")
            );
            return "failure";
        }

        // 未付成功的中间态（WAIT_BUYER_PAY 等）直接应答 success：报文已验签且我方无需动作，
        // 回 failure 会让支付宝反复重投同一条无意义通知。
        if (!result.paid()) {
            log.info("Alipay notify ignored non-paid trade_status for paymentNo={}", result.paymentNo());
            return "success";
        }

        PaymentCallbackRequest callback = new PaymentCallbackRequest(
            result.paymentNo(),
            null,
            "SUCCESS",
            result.tradeNo(),
            result.amount(),
            null,
            null,
            // 验签已由 RSA2 完成，此处不再走 HMAC 校验，故签名位留空。
            ""
        );

        try {
            repository.handlePaymentCallback("alipay", callback);
            return "success";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 金额不符、订单状态不允许等：明确回 failure，让支付宝重投，
            // 同时 recordPaymentCallback 已把失败原因落库供排查。
            log.error("Alipay notify settle failed for paymentNo={}: {}", result.paymentNo(), ex.getMessage());
            return "failure";
        }
    }
}
