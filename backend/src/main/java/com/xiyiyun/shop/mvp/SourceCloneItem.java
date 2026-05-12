package com.xiyiyun.shop.mvp;

public record SourceCloneItem(
    String supplierGoodsId,
    String supplierGoodsName,
    String status,
    Long goodsId,
    Long channelId,
    String message
) {
}
