package com.xiyiyun.shop.mvp;

import java.util.List;

public record SourceCloneRequest(
    List<String> supplierGoodsIds,
    List<SourceCloneConfigItem> items,
    Long categoryId,
    Integer priority,
    Integer timeoutSeconds
) {
}
