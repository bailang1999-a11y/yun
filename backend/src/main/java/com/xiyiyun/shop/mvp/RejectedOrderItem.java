package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 管理端订单列表中的业务拒绝记录，不进入正常订单状态机。 */
public record RejectedOrderItem(
    String orderNo,
    Long userId,
    String buyerAccount,
    Long goodsId,
    String goodsName,
    GoodsType goodsType,
    String platform,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal amount,
    BigDecimal externalMaxAmount,
    BigDecimal expectedAmount,
    String status,
    String deliveryType,
    String rechargeAccount,
    Map<String, String> rechargeFields,
    String buyerRemark,
    String requestId,
    List<String> deliveryItems,
    List<ChannelAttemptItem> channelAttempts,
    String deliveryMessage,
    String rejectionCode,
    String rejectionReason,
    OffsetDateTime createdAt,
    OffsetDateTime rejectedAt,
    OffsetDateTime deliveredAt,
    Integer attemptCount
) {
    public RejectedOrderItem {
        rechargeFields = rechargeFields == null ? Map.of() : Map.copyOf(rechargeFields);
        deliveryItems = deliveryItems == null ? List.of() : List.copyOf(deliveryItems);
        channelAttempts = channelAttempts == null ? List.of() : List.copyOf(channelAttempts);
    }
}
