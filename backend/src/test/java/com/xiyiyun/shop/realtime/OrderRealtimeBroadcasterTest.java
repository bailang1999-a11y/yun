package com.xiyiyun.shop.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.OrderItem;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class OrderRealtimeBroadcasterTest {
    @Test
    void adminReceivesAllOrdersWhileH5ReceivesOnlyOwnOrder() throws Exception {
        OrderRealtimeBroadcaster broadcaster = new OrderRealtimeBroadcaster(new ObjectMapper().findAndRegisterModules());
        List<String> adminMessages = new ArrayList<>();
        List<String> ownerMessages = new ArrayList<>();
        List<String> otherUserMessages = new ArrayList<>();
        WebSocketSession admin = session(Map.of("role", "admin"), adminMessages);
        WebSocketSession owner = session(Map.of("role", "h5", "userId", 90001L), ownerMessages);
        WebSocketSession otherUser = session(Map.of("role", "h5", "userId", 90002L), otherUserMessages);
        WebSocketSession anonymous = session(Map.of(), new ArrayList<>());

        broadcaster.addSession(admin);
        broadcaster.addSession(owner);
        broadcaster.addSession(otherUser);
        broadcaster.addSession(anonymous);
        broadcaster.publish(orderForUser(90001L));

        assertThat(adminMessages).hasSize(1);
        assertThat(ownerMessages).hasSize(1);
        assertThat(otherUserMessages).isEmpty();
        assertThat(adminMessages.get(0)).contains("\"type\":\"ORDER_UPDATED\"", "\"userId\":90001");
        assertThat(ownerMessages.get(0)).contains("\"type\":\"ORDER_UPDATED\"", "\"userId\":90001");
    }

    private static WebSocketSession session(Map<String, Object> attributes, List<String> sentPayloads) {
        return new RecordingWebSocketSession(attributes, sentPayloads);
    }

    private static OrderItem orderForUser(Long userId) {
        return new OrderItem(
            "ORDER-WS-001",
            userId,
            "buyer",
            10001L,
            "card goods",
            GoodsType.CARD,
            "h5",
            1,
            BigDecimal.valueOf(6.90),
            BigDecimal.valueOf(6.90),
            OrderStatus.UNPAID,
            "",
            "",
            "request-ws",
            null,
            null,
            List.of(),
            List.of(),
            "created",
            OffsetDateTime.now(),
            null,
            null
        );
    }

    private static final class RecordingWebSocketSession implements WebSocketSession {
        private final Map<String, Object> attributes;
        private final List<String> sentPayloads;
        private boolean open = true;

        private RecordingWebSocketSession(Map<String, Object> attributes, List<String> sentPayloads) {
            this.attributes = new HashMap<>(attributes);
            this.sentPayloads = sentPayloads;
        }

        @Override
        public String getId() {
            return "test-session";
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws/orders");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 8192;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 8192;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            sentPayloads.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
