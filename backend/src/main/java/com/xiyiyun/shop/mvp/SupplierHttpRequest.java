package com.xiyiyun.shop.mvp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 一次上游 HTTP 调用的声明。
 *
 * <p>{@link #idempotent()} 标记查询/写操作语义。查询类（余额/测连通/订单状态/商品列表）
 * 保持原有重试策略；下单等写操作只在尚未建立连接时失败才重试，且总共最多三次。</p>
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

    /** 下单等写操作：仅连接建立失败时由客户端安全重试。 */
    public static SupplierHttpRequest mutation(URI uri, String body, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, Map.of(), timeout, action, false);
    }

    public static SupplierHttpRequest mutation(URI uri, String body, Map<String, String> headers, Duration timeout, String action) {
        return new SupplierHttpRequest(uri, body, headers, timeout, action, false);
    }
}
