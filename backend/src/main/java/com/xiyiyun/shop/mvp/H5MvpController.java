package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/h5")
public class H5MvpController {
    private final InMemoryShopRepository repository;

    public H5MvpController(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryItem>> categories() {
        return ApiResponse.ok(repository.listCategories());
    }

    @GetMapping("/recharge-fields")
    public ApiResponse<List<RechargeFieldItem>> rechargeFields() {
        return ApiResponse.ok(repository.listRechargeFields(true));
    }

    @GetMapping("/settings")
    public ApiResponse<SystemSettingItem> settings() {
        return ApiResponse.ok(repository.systemSetting());
    }

    @GetMapping("/payment-channels")
    public ApiResponse<List<PaymentChannelItem>> paymentChannels(@RequestParam(required = false) String terminal) {
        return ApiResponse.ok(repository.listEnabledPaymentChannels(terminal));
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthSession<UserItem>> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        try {
            return ApiResponse.ok(repository.loginUser(request, clientIp(servletRequest)));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/auth/sms/login")
    public ApiResponse<AuthSession<UserItem>> smsLogin(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        try {
            return ApiResponse.ok(repository.loginUser(request, clientIp(servletRequest)));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/auth")
    public ApiResponse<AuthSession<UserItem>> auth(@RequestBody UserAuthRequest request, HttpServletRequest servletRequest) {
        try {
            return ApiResponse.ok(repository.authenticateUser(request, clientIp(servletRequest)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/auth/captcha-config")
    public ApiResponse<CaptchaChallengeItem> captchaConfig(@RequestParam(required = false) String terminal) {
        return ApiResponse.ok(repository.captchaChallenge(terminal));
    }

    @PostMapping("/auth/slider")
    public ApiResponse<String> slider(@RequestBody(required = false) SendSmsCodeRequest request) {
        return ApiResponse.ok(repository.createSliderToken(request == null ? "h5" : request.terminal()));
    }

    @PostMapping("/auth/sms/send")
    public ApiResponse<String> sendSmsCode(@RequestBody SendSmsCodeRequest request, HttpServletRequest servletRequest) {
        try {
            return ApiResponse.ok(repository.sendUserLoginSmsCode(request, clientIp(servletRequest)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/auth/logout")
    public ApiResponse<String> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        repository.logoutUser(token);
        return ApiResponse.ok("ok");
    }

    @GetMapping("/users/me")
    public ApiResponse<UserItem> me(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findUserByToken(token)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @GetMapping("/member-api")
    public ApiResponse<MemberApiCredentialItem> memberApi(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findUserByToken(token)
            .map(user -> ApiResponse.ok(repository.memberCredentialForUser(user.id())))
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @PostMapping("/member-api")
    public ApiResponse<MemberApiCredentialItem> saveMemberApi(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestBody MemberApiCredentialRequest request
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.saveMemberCredential(userId, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/users/me/password")
    public ApiResponse<UserItem> changePassword(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestBody PasswordChangeRequest request
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.changeUserPassword(userId, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/recharge-requests")
    public ApiResponse<RechargeRequestResult> createRechargeRequest(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestBody RechargeRequest request
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.createRechargeRequest(userId, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/goods")
    public ApiResponse<?> goods(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String platform,
        @RequestParam(required = false) Long userGroupId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer pageSize
    ) {
        Long effectiveUserGroupId = repository.findUserByToken(token).map(UserItem::groupId).orElse(userGroupId);
        List<GoodsItem> goods = repository.listGoods(categoryId, search, platform, effectiveUserGroupId, false);
        if (page == null && pageSize == null) {
            return ApiResponse.ok(goods);
        }

        int safePage = Math.max(page == null ? 1 : page, 1);
        int safePageSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 100);
        int from = Math.min((safePage - 1) * safePageSize, goods.size());
        int to = Math.min(from + safePageSize, goods.size());
        return ApiResponse.ok(new PageResult<>(goods.subList(from, to), goods.size(), safePage, safePageSize));
    }

    @GetMapping("/goods/{id}")
    public ApiResponse<GoodsItem> goodsDetail(
        @PathVariable Long id,
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestParam(required = false) Long userGroupId
    ) {
        Long effectiveUserGroupId = repository.findUserByToken(token).map(UserItem::groupId).orElse(userGroupId);
        return repository.findGoods(id, effectiveUserGroupId, false)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("goods not found"));
    }

    @PostMapping("/orders")
    public ApiResponse<OrderItem> createOrder(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestBody CreateOrderRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.createOrder(request, userId, clientIp(servletRequest), "h5"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/pay")
    public ApiResponse<OrderItem> payOrder(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable String orderNo,
        @RequestBody(required = false) PayOrderRequest request
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.payOrder(orderNo, userId, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<OrderItem> cancelOrder(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable String orderNo
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.cancelOrder(orderNo, userId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderItem>> orders(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findUserByToken(token)
            .map(user -> ApiResponse.ok(repository.listOrdersForUser(user.id())))
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderItem> orderDetail(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable String orderNo
    ) {
        Long userId = repository.findUserByToken(token).map(UserItem::id).orElse(null);
        if (userId == null) {
            return ApiResponse.fail("unauthorized");
        }
        return repository.findOrderForUser(orderNo, userId)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("order not found"));
    }

    @GetMapping("/payments/{paymentNo}")
    public ApiResponse<PaymentItem> paymentDetail(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable String paymentNo
    ) {
        Long userId = repository.findUserByToken(token).map(UserItem::id).orElse(null);
        if (userId == null) {
            return ApiResponse.fail("unauthorized");
        }
        return repository.findPaymentForUser(paymentNo, userId)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("payment not found"));
    }

    @GetMapping("/orders/{orderNo}/delivery")
    public ApiResponse<DeliveryResult> delivery(
        @RequestHeader(value = "Authorization", required = false) String token,
        @PathVariable String orderNo
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.deliveryResult(orderNo, userId));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
