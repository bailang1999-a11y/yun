package com.xiyiyun.shop.mvp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 适配器声明式 HTTP 配置。
 *
 * <p>任务B 的核心：把「各家 HTTP 差异」变成适配器自己声明的数据，
 * 而不是在 {@link SupplierHttpClient} 里 if-else 判断供应商名字。
 * 现存两处真实差异都由本类承载：</p>
 * <ul>
 *   <li>凡尘 fanchen：{@code charset=GBK} + {@code Content-Type: ...; charset=GBK}
 *       （请求体编码与响应体解码都走 GBK）</li>
 *   <li>卡速售 kasushou：每请求附加 {@code Sign}/{@code Timestamp}/{@code UserId} 自定义头
 *       （通过 {@link SupplierHttpRequest#extraHeaders()} 逐请求声明，因为签名值随请求体变化）</li>
 * </ul>
 */
public record SupplierHttpProfile(
    String supplierCode,
    Charset charset,
    String contentType,
    String accept,
    String userAgent,
    Map<String, String> staticHeaders
) {
    public SupplierHttpProfile {
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
        staticHeaders = staticHeaders == null ? Map.of() : Map.copyOf(staticHeaders);
    }

    /** JSON + UTF-8，7 家里 5 家用这个（kasushou/kakayun/fulu/fengzhushou/chengquan）。 */
    public static SupplierHttpProfile json(String supplierCode) {
        return new SupplierHttpProfile(
            supplierCode,
            StandardCharsets.UTF_8,
            "application/json",
            "application/json",
            "xiyiyun-" + supplierCode + "-client/1.0",
            Map.of()
        );
    }

    /** 表单 + 指定编码，用于 jingzhao(UTF-8) 与 fanchen(GBK)。 */
    public static SupplierHttpProfile form(String supplierCode, Charset charset) {
        String charsetName = charset.name();
        return new SupplierHttpProfile(
            supplierCode,
            charset,
            "application/x-www-form-urlencoded; charset=" + charsetName,
            "application/json",
            "xiyiyun-" + supplierCode + "-client/1.0",
            Map.of()
        );
    }

    Map<String, String> mergedHeaders(Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Accept", accept);
        headers.put("User-Agent", userAgent);
        headers.putAll(staticHeaders);
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        return headers;
    }
}
