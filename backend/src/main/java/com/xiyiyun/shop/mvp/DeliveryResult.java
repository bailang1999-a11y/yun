package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.util.List;

public record DeliveryResult(
    String orderNo,
    OrderStatus status,
    GoodsType goodsType,
    String rechargeAccount,
    List<String> deliveryItems,
    String message,
    List<DeliveryCardItem> cards,
    boolean viewedBefore
) {
}
