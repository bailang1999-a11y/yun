package com.xiyiyun.shop.mvp;

import java.util.List;

public record SourceCloneResult(
    Integer createdCount,
    Integer skippedCount,
    Integer failedCount,
    List<SourceCloneItem> items
) {
}
