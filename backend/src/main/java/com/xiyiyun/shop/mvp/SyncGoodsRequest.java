package com.xiyiyun.shop.mvp;

public record SyncGoodsRequest(
    Long cateId,
    String keyword,
    Integer page,
    Integer limit
) {
}
