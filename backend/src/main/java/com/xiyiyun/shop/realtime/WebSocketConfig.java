package com.xiyiyun.shop.realtime;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final OrderWebSocketHandler orderWebSocketHandler;
    private final OrderWebSocketAuthInterceptor authInterceptor;
    private final String corsAllowedOrigins;

    public WebSocketConfig(
        OrderWebSocketHandler orderWebSocketHandler,
        OrderWebSocketAuthInterceptor authInterceptor,
        @Value("${xiyiyun.cors.allowed-origins:*}") String corsAllowedOrigins
    ) {
        this.orderWebSocketHandler = orderWebSocketHandler;
        this.authInterceptor = authInterceptor;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(orderWebSocketHandler, "/ws/orders")
            .addInterceptors(authInterceptor)
            .setAllowedOriginPatterns(allowedOriginPatterns());
    }

    private String[] allowedOriginPatterns() {
        String[] origins = Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
        return origins.length == 0 ? new String[] { "*" } : origins;
    }
}
