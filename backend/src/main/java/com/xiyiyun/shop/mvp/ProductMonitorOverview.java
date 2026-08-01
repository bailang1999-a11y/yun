package com.xiyiyun.shop.mvp;

import java.util.List;

public record ProductMonitorOverview(
    List<ProductMonitorItem> items,
    List<ProductMonitorLogItem> logs,
    Integer total,
    Integer page,
    Integer pageSize,
    Integer activeTotal,
    Integer logTotal
) {
}
