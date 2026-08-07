package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 统一的上游 HTTP 客户端（任务B）。
 *
 * <p>替换掉原先 7 处各自 {@code HttpClient.newBuilder().build()} 的写法。三点统一：</p>
 * <ul>
 *   <li><b>连接池</b>：全进程共享单个 {@link HttpClient} 实例（JDK HttpClient 内部自带连接复用）。
 *       原代码每次调用都新建实例，连接无法复用。</li>
 *   <li><b>超时</b>：连接超时与读取超时都取供应商配置的 {@code timeoutSeconds}，语义与原代码一致。</li>
 *   <li><b>重试</b>：查询请求保持原有一次退避重试；下单请求仅在连接尚未建立时失败才重试，
 *       总共最多三次。下单读取超时、HTTP 错误和业务错误不重试，避免重复充值。</li>
 * </ul>
 *
 * <p>本类<b>不</b>包含任何供应商名字的条件分支：编码、Content-Type、自定义头
 * 全部来自 {@link SupplierHttpProfile} 与 {@link SupplierHttpRequest}。</p>
 */
@Component
public class SupplierHttpClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int QUERY_MAX_ATTEMPTS = 2;
    private static final int MUTATION_MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

    private final HttpClient sharedClient;

    public SupplierHttpClient() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    SupplierHttpClient(HttpClient sharedClient) {
        this.sharedClient = sharedClient;
    }

    /**
     * 发起调用并把响应体解析为 JSON。
     *
     * @throws SupplierTransportException 连接失败/超时/5xx/响应非 JSON —— 结果未知，调用方须转中间态
     * @throws SupplierBusinessException  4xx —— 上游明确拒绝
     */
    public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
        String responseBody = post(profile, request);
        try {
            return OBJECT_MAPPER.readTree(responseBody);
        } catch (JsonProcessingException ex) {
            throw new SupplierTransportException(
                profile.supplierCode(),
                request.action(),
                profile.supplierCode() + " " + request.action() + " failed: invalid JSON response",
                null,
                ex
            );
        }
    }

    public String post(SupplierHttpProfile profile, SupplierHttpRequest request) {
        Charset charset = profile.charset();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
            .timeout(request.timeout())
            .version(HttpClient.Version.HTTP_1_1)
            .POST(HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body(), charset));
        profile.mergedHeaders(request.extraHeaders()).forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        int maxAttempts = request.idempotent() ? QUERY_MAX_ATTEMPTS : MUTATION_MAX_ATTEMPTS;
        SupplierTransportException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = sharedClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(charset)
                );
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status >= 500) {
                    lastFailure = new SupplierTransportException(
                        profile.supplierCode(),
                        request.action(),
                        profile.supplierCode() + " " + request.action() + " failed: HTTP "
                            + status + " " + abbreviate(response.body()),
                        status,
                        null
                    );
                    if (request.idempotent() && attempt < maxAttempts) {
                        backoff();
                        continue;
                    }
                    throw lastFailure;
                }
                // 4xx：上游明确拒绝该请求，不重试。
                throw new SupplierBusinessException(
                    profile.supplierCode(),
                    request.action(),
                    String.valueOf(status),
                    profile.supplierCode() + " " + request.action() + " failed: HTTP "
                        + status + " " + abbreviate(response.body())
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SupplierTransportException(
                    profile.supplierCode(),
                    request.action(),
                    profile.supplierCode() + " " + request.action() + " interrupted",
                    null,
                    ex
                );
            } catch (IOException ex) {
                lastFailure = new SupplierTransportException(
                    profile.supplierCode(),
                    request.action(),
                    profile.supplierCode() + " " + request.action() + " failed: " + ex.getMessage(),
                    null,
                    ex
                );
                if (attempt < maxAttempts && (request.idempotent() || isConnectionFailure(ex))) {
                    backoff();
                    continue;
                }
                throw lastFailure;
            } catch (UnresolvedAddressException ex) {
                lastFailure = new SupplierTransportException(
                    profile.supplierCode(),
                    request.action(),
                    profile.supplierCode() + " " + request.action() + " failed: " + ex.getMessage(),
                    null,
                    ex
                );
                if (attempt < maxAttempts) {
                    backoff();
                    continue;
                }
                throw lastFailure;
            }
        }
        throw lastFailure == null
            ? new SupplierTransportException(profile.supplierCode(), request.action(), "request failed")
            : lastFailure;
    }

    private static boolean isConnectionFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpConnectTimeoutException
                || current instanceof ConnectException
                || current instanceof UnknownHostException
                || current instanceof NoRouteToHostException
                || current instanceof UnresolvedAddressException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void backoff() {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    /** 表单编码，编码字符集由适配器的 profile 决定（凡尘走 GBK）。 */
    public static String formUrlEncoded(Map<String, Object> body, Charset charset) {
        Map<String, Object> safeBody = body == null ? new LinkedHashMap<>() : body;
        return safeBody.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), charset)
                + "=" + URLEncoder.encode(entry.getValue() == null ? "" : String.valueOf(entry.getValue()), charset))
            .collect(Collectors.joining("&"));
    }
}
