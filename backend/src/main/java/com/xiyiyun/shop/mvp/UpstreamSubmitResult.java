package com.xiyiyun.shop.mvp;

import java.util.List;

/**
 * 归一化的上游下单结果。
 *
 * <p>相比被替换的 {@code ProcurementSubmitResult} 多了 {@link #upstreamOrderNo()}：
 * 缺陷 A4 要求把上游订单号落到 {@code orders.upstream_order_no}（批次1 已建列 + uk_orders_upstream），
 * 用于后续对账/轮询确认真实结果，因此下单成功路径必须显式把它带出来。</p>
 */
public record UpstreamSubmitResult(
    List<String> deliveryItems,
    String attemptMessage,
    String upstreamOrderNo
) {
    public UpstreamSubmitResult {
        deliveryItems = deliveryItems == null ? List.of() : List.copyOf(deliveryItems);
        upstreamOrderNo = upstreamOrderNo == null ? "" : upstreamOrderNo.trim();
    }
}
