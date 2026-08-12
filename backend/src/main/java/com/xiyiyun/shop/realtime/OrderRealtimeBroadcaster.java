package com.xiyiyun.shop.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.OrderEventPublisher;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.ProductMonitorLogItem;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class OrderRealtimeBroadcaster implements OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderRealtimeBroadcaster.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final Map<WebSocketSession, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public OrderRealtimeBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void addSession(WebSocketSession session) {
        if (session != null) {
            sessions.put(session, new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES));
        }
    }

    public void removeSession(WebSocketSession session) {
        if (session != null) {
            sessions.remove(session);
        }
    }

    /** WebSocket ping 也必须经过同一个串行发送包装，避免和订单事件并发写入。 */
    public void sendPing(WebSocketSession rawSession) {
        WebSocketSession session = sessions.get(rawSession);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
        } catch (IOException | RuntimeException ex) {
            removeSession(rawSession);
            log.debug("remove websocket session after ping failure: {}", rawSession.getId(), ex);
        }
    }

    @Override
    public void publish(OrderItem order) {
        publishEvent(OrderRealtimeEvent.updated(order));
    }

    @Override
    public void publishProductMonitorLog(ProductMonitorLogItem log) {
        publishEvent(OrderRealtimeEvent.productMonitor(log));
    }

    private void publishEvent(OrderRealtimeEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            return;
        }

        sessions.entrySet().removeIf(entry -> {
            WebSocketSession rawSession = entry.getKey();
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) {
                return true;
            }
            return canReceive(rawSession, event) && !send(rawSession, session, payload);
        });
    }

    private boolean canReceive(WebSocketSession session, OrderRealtimeEvent event) {
        Object role = session.getAttributes().get("role");
        if ("admin".equals(role)) {
            return true;
        }
        if (!"h5".equals(role)) {
            return false;
        }
        if (!"ORDER_UPDATED".equals(event.type()) || event.order() == null) {
            return false;
        }
        Object userId = session.getAttributes().get("userId");
        return userId instanceof Long id && id.equals(event.order().userId());
    }

    private boolean send(WebSocketSession rawSession, WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
            return true;
        } catch (IOException | RuntimeException ex) {
            log.debug("remove websocket session after order event failure: {}", rawSession.getId(), ex);
            return false;
        }
    }
}
