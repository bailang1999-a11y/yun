package com.xiyiyun.shop.realtime;

import com.xiyiyun.shop.mvp.InMemoryShopRepository;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OrderWebSocketAuthInterceptor implements HandshakeInterceptor {
    private final InMemoryShopRepository repository;

    public OrderWebSocketAuthInterceptor(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
            .build()
            .getQueryParams()
            .getFirst("token");

        if (repository.findAdminByToken(token).isPresent()) {
            attributes.put("role", "admin");
            return true;
        }
        return repository.findUserByToken(token)
            .map(user -> {
                attributes.put("role", "h5");
                attributes.put("userId", user.id());
                return true;
            })
            .orElse(false);
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
    }
}
