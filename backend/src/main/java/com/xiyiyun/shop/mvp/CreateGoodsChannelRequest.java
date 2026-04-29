package com.xiyiyun.shop.mvp;

public record CreateGoodsChannelRequest(
    Long supplierId,
    String supplierGoodsId,
    Integer priority,
    Integer timeoutSeconds,
    String status
) {
}
