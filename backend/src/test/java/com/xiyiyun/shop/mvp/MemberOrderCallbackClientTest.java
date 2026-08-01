package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemberOrderCallbackClientTest {
    @Test
    void connectsThroughThePinnedAddressAndAcceptsAnEmpty2xxResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> eventId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/callback", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            eventId.set(exchange.getRequestHeaders().getFirst("X-Xiyi-Event-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://callback.example.test:" + server.getAddress().getPort() + "/callback");
            MemberCallbackUrlPolicy.ResolvedTarget target = new MemberCallbackUrlPolicy.ResolvedTarget(
                uri, uri.getHost(), new InetAddress[]{InetAddress.getLoopbackAddress()}
            );

            new MemberOrderCallbackClient().postResolved(
                target, "{\"eventId\":\"evt_1\"}", Map.of("X-Xiyi-Event-Id", "evt_1"), 5
            );

            assertThat(body.get()).isEqualTo("{\"eventId\":\"evt_1\"}");
            assertThat(eventId.get()).isEqualTo("evt_1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverCopiesARejectedResponseBodyIntoThePersistedErrorMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/callback", exchange -> {
            byte[] response = "rejected CARD-SECRET 13800138000".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://callback.example.test:" + server.getAddress().getPort() + "/callback");
            MemberCallbackUrlPolicy.ResolvedTarget target = new MemberCallbackUrlPolicy.ResolvedTarget(
                uri, uri.getHost(), new InetAddress[]{InetAddress.getLoopbackAddress()}
            );

            assertThatThrownBy(() -> new MemberOrderCallbackClient().postResolved(
                target, "{}", Map.of(), 5
            )).isInstanceOf(SupplierTransportException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("CARD-SECRET")
                .hasMessageNotContaining("13800138000");
        } finally {
            server.stop(0);
        }
    }
}
