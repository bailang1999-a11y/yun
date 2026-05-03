package com.xiyiyun.shop.realtime;

import com.xiyiyun.shop.mvp.ProductMonitorLogItem;
import com.xiyiyun.shop.mvp.OrderItem;
import java.time.OffsetDateTime;

public record OrderRealtimeEvent(
    String type,
    OrderItem order,
    ProductMonitorLogItem monitorLog,
    OffsetDateTime emittedAt
) {
    public static OrderRealtimeEvent updated(OrderItem order) {
        return new OrderRealtimeEvent("ORDER_UPDATED", order, null, OffsetDateTime.now());
    }

    public static OrderRealtimeEvent productMonitor(ProductMonitorLogItem log) {
        return new OrderRealtimeEvent("PRODUCT_MONITOR_LOG", null, log, OffsetDateTime.now());
    }
}
