package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 归一化的上游订单状态快照。
 *
 * <p>替换原先 7 个各自独立的 {@code XxxOrderStatus} record（以及配套的 7 个
 * {@code enrichAttempt} / 7 个 {@code mergedDeliveryItems} 重载）。
 * 各家的状态码差异由适配器自己翻译成这里的 {@link #localStatus()} 与
 * {@link #upstreamStatusLabel()}，仓储层不再需要知道任何一家的状态码含义。</p>
 *
 * @param localStatus         映射后的本地订单状态（DELIVERED / FAILED / PROCURING）
 * @param upstreamStatusLabel 上游状态英文标签，写入渠道尝试记录
 * @param deliveryMessage     面向用户的中文状态描述
 * @param cards               上游返回的卡密行，无卡类商品为空
 */
public record UpstreamOrderSnapshot(
    String upstreamOrderNo,
    String externalOrderNo,
    OrderStatus localStatus,
    String upstreamStatusLabel,
    String deliveryMessage,
    String hints,
    BigDecimal totalPrice,
    String goodsName,
    List<String> cards,
    String rawResponse
) {
    public UpstreamOrderSnapshot {
        cards = cards == null ? List.of() : List.copyOf(cards);
        upstreamOrderNo = upstreamOrderNo == null ? "" : upstreamOrderNo;
        externalOrderNo = externalOrderNo == null ? "" : externalOrderNo;
        goodsName = goodsName == null ? "" : goodsName;
        hints = hints == null ? "" : hints;
    }

    /**
     * 在快照构造时未知本地回落状态（回调入口先解析报文、后查订单）的场景下补齐回落。
     *
     * <p>7 家的映射函数都是 {@code default -> fallback == null ? PROCURING : fallback}，
     * 即 PROCURING 只会出现在「上游未给终态」的默认分支，因此此处按 fallback 补齐
     * 与原实现「先查到订单再用 order.status() 作 fallback」等价。</p>
     */
    public OrderStatus resolvedLocalStatus(OrderStatus fallback) {
        if (fallback == null || localStatus != OrderStatus.PROCURING) {
            return localStatus;
        }
        return fallback;
    }

    /**
     * 合并发货明细。原先 7 个 {@code mergedDeliveryItems} 重载逻辑完全一致
     * （上游订单号 / 外部订单号 / 上游金额 / 上游结果 + 卡密），此处收敛为一处。
     */
    public List<String> mergedDeliveryItems(List<String> currentItems) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (!upstreamOrderNo.isBlank()) {
            items.add("上游订单号：" + upstreamOrderNo);
        }
        if (!externalOrderNo.isBlank()) {
            items.add("外部订单号：" + externalOrderNo);
        }
        if (totalPrice != null) {
            items.add("上游金额：" + totalPrice);
        }
        if (!hints.isBlank()) {
            items.add("上游结果：" + hints);
        }
        items.addAll(cards);
        return List.copyOf(items);
    }
}
