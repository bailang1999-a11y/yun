package com.xiyiyun.shop.mvp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
class MemberOrderCallbackClient {
    void post(String callbackUrl, String body, Map<String, String> headers, int timeoutSeconds) {
        MemberCallbackUrlPolicy.ResolvedTarget target = MemberCallbackUrlPolicy.resolvePublicTarget(callbackUrl);
        postResolved(target, body, headers, timeoutSeconds);
    }

    void postResolved(
        MemberCallbackUrlPolicy.ResolvedTarget target,
        String body,
        Map<String, String> headers,
        int timeoutSeconds
    ) {
        Timeout timeout = Timeout.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 120)));
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(timeout)
            .setConnectTimeout(timeout)
            .setResponseTimeout(timeout)
            .setRedirectsEnabled(false)
            .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PinnedDnsResolver(target))
            .setMaxConnTotal(1)
            .setMaxConnPerRoute(1)
            .build();
        try (CloseableHttpClient http = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build()) {
            HttpPost request = new HttpPost(target.uri());
            headers.forEach(request::setHeader);
            request.setEntity(new StringEntity(
                body == null ? "" : body,
                ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)
            ));
            http.execute(request, response -> {
                int status = response.getCode();
                EntityUtils.consume(response.getEntity());
                if (status >= 200 && status < 300) {
                    return null;
                }
                if (status >= 500) {
                    throw new SupplierTransportException(
                        "member-order-callback", "order status callback",
                        "member order callback failed: HTTP " + status, status, null
                    );
                }
                throw new SupplierBusinessException(
                    "member-order-callback", "order status callback", String.valueOf(status),
                    "member order callback failed: HTTP " + status
                );
            });
        } catch (IOException ex) {
            throw new SupplierTransportException(
                "member-order-callback", "order status callback",
                "member order callback failed: " + ex.getMessage(), null, ex
            );
        }
    }

    static final class PinnedDnsResolver implements DnsResolver {
        private final String host;
        private final InetAddress[] addresses;

        PinnedDnsResolver(MemberCallbackUrlPolicy.ResolvedTarget target) {
            this.host = target.host().toLowerCase(Locale.ROOT);
            this.addresses = target.addresses();
        }

        @Override
        public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
            if (!host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
                throw new UnknownHostException("unexpected callback host");
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
            resolve(requestedHost);
            return host;
        }
    }
}
