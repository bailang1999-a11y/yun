package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/member")
public class MemberMvpController {
    private final InMemoryShopRepository repository;

    public MemberMvpController(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/balance")
    public ApiResponse<UserItem> balance(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        HttpServletRequest request
    ) {
        try {
            return ApiResponse.ok(auth(appKey, timestamp, nonce, signature, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/goods")
    public ApiResponse<List<GoodsItem>> goods(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "h5") String platform,
        HttpServletRequest request
    ) {
        try {
            UserItem user = auth(appKey, timestamp, nonce, signature, request);
            return ApiResponse.ok(repository.listGoods(categoryId, search, platform, user.groupId(), false));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders")
    public ApiResponse<OrderItem> createOrder(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        @RequestBody CreateOrderRequest body,
        HttpServletRequest request
    ) {
        try {
            UserItem user = auth(appKey, timestamp, nonce, signature, request);
            return ApiResponse.ok(repository.createMemberOrder(body, user.id()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderItem> order(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        @PathVariable String orderNo,
        HttpServletRequest request
    ) {
        try {
            UserItem user = auth(appKey, timestamp, nonce, signature, request);
            return repository.findOrderForUser(orderNo, user.id())
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail("order not found"));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/orders/by-request/{requestId}")
    public ApiResponse<OrderItem> orderByRequest(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        @PathVariable String requestId,
        HttpServletRequest request
    ) {
        try {
            UserItem user = auth(appKey, timestamp, nonce, signature, request);
            return repository.listOrdersForUser(user.id()).stream()
                .filter(order -> requestId.equals(order.requestId()))
                .findFirst()
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail("order not found"));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    private UserItem auth(String appKey, String timestamp, String nonce, String signature, HttpServletRequest request) {
        return repository.authenticateMemberApi(appKey, timestamp, nonce, signature, request.getRequestURI(), clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
