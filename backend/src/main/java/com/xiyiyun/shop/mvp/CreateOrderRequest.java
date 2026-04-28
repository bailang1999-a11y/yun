package com.xiyiyun.shop.mvp;

public record CreateOrderRequest(
    Long goodsId,
    Integer quantity,
    String rechargeAccount,
    String buyerRemark,
    String requestId
) {
}
