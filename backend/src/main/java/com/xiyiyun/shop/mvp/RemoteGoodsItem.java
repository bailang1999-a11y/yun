package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.Map;

public record RemoteGoodsItem(
    String supplierGoodsId,
    String goodsName,
    String goodsType,
    BigDecimal goodsPrice,
    BigDecimal faceValue,
    Integer stockNum,
    String status,
    Boolean canBuy,
    Boolean canNoBuy,
    Map<String, Object> raw
) {
}
