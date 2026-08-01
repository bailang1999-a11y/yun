package com.xiyiyun.shop.mvp;

import org.springframework.util.StringUtils;

/**
 * 上游商品 ID 归一化（原 {@code kasushouGoodsId}）：能转 long 就转 long，否则原样传字符串。
 * 卡速售、咔咔云共用，行为零改动。
 */
final class SupplierGoodsId {
    private SupplierGoodsId() {
    }

    static Object of(String supplierGoodsId) {
        String value = SupplierJson.defaultText(supplierGoodsId, "").trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("上游商品ID为空");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return value;
        }
    }
}
