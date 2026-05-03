package com.xiyiyun.shop.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final OrderWebSocketHandler orderWebSocketHandler;
    private final OrderWebSocketAuthInterceptor authInterceptor;

    public WebSocketConfig(OrderWebSocketHandler orderWebSocketHandler, OrderWebSocketAuthInterceptor authInterceptor) {
        this.orderWebSocketHandler = orderWebSocketHandler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(orderWebSocketHandler, "/ws/orders")
            .addInterceptors(authInterceptor)
            .setAllowedOriginPatterns("*");
    }
}
