package com.xiyiyun.shop.mvp;

public record CreateOrderRequest(
    Long goodsId,
    Integer quantity,
    String rechargeAccount,
    String buyerRemark,
    String requestId,
    String terminal
) {
    public CreateOrderRequest(
        Long goodsId,
        Integer quantity,
        String rechargeAccount,
        String buyerRemark,
        String requestId
    ) {
        this(goodsId, quantity, rechargeAccount, buyerRemark, requestId, null);
    }
}
