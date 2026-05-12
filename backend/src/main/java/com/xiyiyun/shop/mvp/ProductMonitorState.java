package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

record ProductMonitorState(
    Long channelId,
    OffsetDateTime lastScanAt,
    OffsetDateTime nextScanAt,
    String lastResult,
    String lastMessage,
    int scanCount,
    int changeCount,
    boolean scanning
) {
    ProductMonitorState start(OffsetDateTime now) {
        return new ProductMonitorState(channelId, lastScanAt, nextScanAt, "SCANNING", "正在扫描上游商品", scanCount, changeCount, true);
    }

    ProductMonitorState finish(OffsetDateTime scannedAt, OffsetDateTime nextAt, String result, String message, boolean changed) {
        return new ProductMonitorState(channelId, scannedAt, nextAt, result, message, scanCount + 1, changed ? changeCount + 1 : changeCount, false);
    }
}
