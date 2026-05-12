package com.xiyiyun.shop.mvp;

public record RechargeFieldRequest(
    String code,
    String label,
    String placeholder,
    String helpText,
    String inputType,
    Boolean required,
    Integer sort,
    Boolean enabled
) {
}
