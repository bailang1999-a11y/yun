package com.xiyiyun.shop.mvp;

import java.util.List;

public record ProductMonitorOverview(
    List<ProductMonitorItem> items,
    List<ProductMonitorLogItem> logs
) {
}
