package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
        } catch (IllegalArgumentException | IllegalStateException ex) {
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
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders")
    public ApiResponse<OrderItem> createOrder(
        @RequestHeader("X-App-Key") String appKey,
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Nonce") String nonce,
        @RequestHeader("X-Signature") String signature,
        @RequestHeader(value = "X-Content-SHA256", required = false) String contentHash,
        @RequestBody String rawBody,
        HttpServletRequest request
    ) {
        try {
            String verifiedContentHash = verifyContentHash(rawBody, contentHash);
            UserItem user = repository.authenticateMemberApi(
                appKey, timestamp, nonce, signature, request.getRequestURI(), clientIp(request), verifiedContentHash
            );
            CreateOrderRequest body = OBJECT_MAPPER.readValue(rawBody, CreateOrderRequest.class);
            return ApiResponse.ok(repository.createMemberOrder(body, user.id(), clientIp(request)));
        } catch (IllegalArgumentException | IllegalStateException | JsonProcessingException ex) {
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
        } catch (IllegalArgumentException | IllegalStateException ex) {
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
            // 批次8C：原来是拉出该用户全部订单再在 Java 里 filter requestId。
            // 会员方靠轮询这个接口确认下单结果，等于每次轮询把历史订单全量读一遍。
            // 改走 (user_id, request_id) 唯一索引等值查找。
            return repository.findOrderByRequestId(user.id(), requestId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail("order not found"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    private UserItem auth(String appKey, String timestamp, String nonce, String signature, HttpServletRequest request) {
        return repository.authenticateMemberApi(appKey, timestamp, nonce, signature, request.getRequestURI(), clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddress)) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return remoteAddress;
    }

    private boolean isTrustedProxy(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    private String verifyContentHash(String rawBody, String suppliedHash) {
        String normalizedHash = suppliedHash == null ? "" : suppliedHash.trim().toLowerCase();
        if (!normalizedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("missing or invalid content hash");
        }
        String actualHash = sha256(rawBody == null ? "" : rawBody);
        if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII), normalizedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("content hash mismatch");
        }
        return normalizedHash;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
