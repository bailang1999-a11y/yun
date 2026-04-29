package com.xiyiyun.shop.mvp;

import java.util.List;

public record CardImportResult(
    Long goodsId,
    int importTotal,
    int successCount,
    int duplicateCount,
    List<Integer> failedLines
) {
}
