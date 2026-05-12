package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.Map;

public record RemoteGoodsItem(
    String supplierGoodsId,
    String goodsName,
    String goodsType,
    String categoryId,
    String categoryName,
    BigDecimal goodsPrice,
    BigDecimal faceValue,
    Integer stockNum,
    String status,
    Boolean canBuy,
    Boolean canNoBuy,
    Boolean connected,
    Long localGoodsId,
    String localGoodsName,
    Long channelId,
    Map<String, Object> raw
) {
}
