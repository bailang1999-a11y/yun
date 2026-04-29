package com.xiyiyun.shop.realtime;

import com.xiyiyun.shop.mvp.OrderItem;
import java.time.OffsetDateTime;

public record OrderRealtimeEvent(
    String type,
    OrderItem order,
    OffsetDateTime emittedAt
) {
    public static OrderRealtimeEvent updated(OrderItem order) {
        return new OrderRealtimeEvent("ORDER_UPDATED", order, OffsetDateTime.now());
    }
}
