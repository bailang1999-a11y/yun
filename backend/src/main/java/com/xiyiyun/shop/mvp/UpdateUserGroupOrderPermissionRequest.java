package com.xiyiyun.shop.mvp;

public record UpdateUserGroupOrderPermissionRequest(
    Boolean orderEnabled,
    Boolean realNameRequiredForOrder,
    Boolean priceLimitEnabled,
    String priceLimitNotice
) {
}
