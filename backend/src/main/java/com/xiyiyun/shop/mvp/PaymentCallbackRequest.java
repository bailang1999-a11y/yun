package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

/**
 * 支付回调报文（批次5 / B1 扩展）。
 *
 * <p>新增 {@code amount}、{@code timestamp}、{@code nonce} 三个字段并全部纳入签名串：
 * <ul>
 *   <li>{@code amount}：本次回调声明的收款金额。旧版签名串<b>不含金额</b>，
 *       攻击者可拿一个合法签名把金额改小，或用小额订单的签名去顶大额订单。</li>
 *   <li>{@code timestamp}：回调发起时间（<b>毫秒</b>级 epoch 的十进制字符串；
 *       10 位则按秒兼容处理），用于时间窗校验，限制签名的可用寿命。</li>
 *   <li>{@code nonce}：一次性随机串，用于重放防护（落 Redis）。</li>
 * </ul>
 *
 * <p>保留 5 参构造以兼容旧调用方与既有测试，缺失的新字段为 {@code null}，
 * 由 {@link PaymentCallbackSignatureVerifier} 依据过渡开关决定是否放行。
 */
public record PaymentCallbackRequest(
    String paymentNo,
    String orderNo,
    String status,
    String channelTradeNo,
    BigDecimal amount,
    String timestamp,
    String nonce,
    String signature
) {
    /** 旧版（v1）报文构造：无 amount/timestamp/nonce。 */
    public PaymentCallbackRequest(
        String paymentNo,
        String orderNo,
        String status,
        String channelTradeNo,
        String signature
    ) {
        this(paymentNo, orderNo, status, channelTradeNo, null, null, null, signature);
    }
}
