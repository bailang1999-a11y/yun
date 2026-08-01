package com.xiyiyun.shop.mvp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 一次上游 HTTP 调用的声明。
 *
 * <p>{@link #idempotent()} 决定是否允许重试：查询类（余额/测连通/订单状态/商品列表）为 true，
 * <b>下单类必须为 false</b>——重试下单会造成上游重复受理与重复扣款。</p>
 */
public record SupplierHttpRequest(
    URI uri,
    String body,
    Map<String, String> extraHeaders,
    Duration timeout,
    String action,
    boolean idempotent
) {
    public SupplierHttpRequest {
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public static SupplierHttpRequest query(URI uri, String body, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, Map.of(), timeout, action, true);
    }

    public static SupplierHttpRequest query(URI uri, String body, Map<String, String> headers, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, headers, timeout, action, true);
    }

    /** 下单等写操作：禁止重试。 */
    public static SupplierHttpRequest mutation(URI uri, String body, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, Map.of(), timeout, action, false);
    }

    public static SupplierHttpRequest mutation(URI uri, String body, Map<String, String> headers, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, headers, timeout, action, false);
    }
}
