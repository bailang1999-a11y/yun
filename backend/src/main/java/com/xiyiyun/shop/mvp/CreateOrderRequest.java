package com.xiyiyun.shop.mvp;

import java.util.Map;

public record CreateOrderRequest(
    Long goodsId,
    Integer quantity,
    String rechargeAccount,
    String buyerRemark,
    String requestId,
    String terminal,
    Map<String, String> rechargeFields
) {
    public CreateOrderRequest(
        Long goodsId,
        Integer quantity,
        String rechargeAccount,
        String buyerRemark,
        String requestId,
        String terminal
    ) {
        this(goodsId, quantity, rechargeAccount, buyerRemark, requestId, terminal, Map.of());
    }

    public CreateOrderRequest(
        Long goodsId,
        Integer quantity,
        String rechargeAccount,
        String buyerRemark,
        String requestId
    ) {
        this(goodsId, quantity, rechargeAccount, buyerRemark, requestId, null, Map.of());
    }
}
