package com.xiyiyun.shop.mvp;

import java.time.Duration;

/**
 * 适配器执行一次上游调用所需的环境。
 *
 * <p>把「适配器需要但不该自己持有」的三样东西显式传入，避免适配器反向依赖仓储实现：</p>
 * <ul>
 *   <li>{@code supplier} —— 供应商配置快照</li>
 *   <li>{@code apiKey} —— 明文密钥。仓储持有 supplierId→明文 的内存映射，
 *       适配器不参与密钥存储，只接收本次调用要用的值</li>
 *   <li>{@code http} —— 统一 HTTP 客户端</li>
 *   <li>{@code goods} —— 上游商品条目映射端口，见 {@link SupplierGoodsMappingPort}</li>
 * </ul>
 */
public record SupplierCallContext(
    SupplierItem supplier,
    String apiKey,
    SupplierHttpClient http,
    SupplierGoodsMappingPort goods,
    String callbackUrl
) {
    /** 与原 {@code normalizedTimeoutSeconds} 一致：null 取 10s，并夹到 [1,60]。 */
    public Duration timeout() {
        Integer configured = supplier.timeoutSeconds();
        int seconds = configured == null ? 10 : Math.max(1, Math.min(configured, 60));
        return Duration.ofSeconds(seconds);
    }

    public String baseUrl() {
        return supplier.baseUrl() == null ? "" : supplier.baseUrl().trim();
    }

    @Override
    public String callbackUrl() {
        return callbackUrl == null ? "" : callbackUrl.trim();
    }
}
