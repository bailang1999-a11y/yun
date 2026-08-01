package com.xiyiyun.shop.mvp;

/**
 * 订单实时事件出站端口：mvp 包只依赖本接口来广播订单与商品监控事件，
 * 不再依赖 realtime 包的具体广播器实现。
 *
 * <p>实现方为 {@code com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster}（出站适配器）。
 */
public interface OrderEventPublisher {
    /** 发布订单变更事件。 */
    void publish(OrderItem order);

    /** 发布商品监控日志事件。 */
    void publishProductMonitorLog(ProductMonitorLogItem log);
}
