package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.ApiResponse;
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

    @PostMapping("/auth/login")
    public ApiResponse<AuthSession<UserItem>> login(@RequestBody LoginRequest request) {
        try {
            return ApiResponse.ok(repository.loginUser(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/auth/sms/login")
    public ApiResponse<AuthSession<UserItem>> smsLogin(@RequestBody LoginRequest request) {
        try {
            return ApiResponse.ok(repository.loginUser(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/users/me")
    public ApiResponse<UserItem> me(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findUserByToken(token)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @GetMapping("/goods")
    public ApiResponse<?> goods(
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String platform,
        @RequestParam(required = false) Long userGroupId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer pageSize
    ) {
        List<GoodsItem> goods = repository.listGoods(categoryId, search, platform, userGroupId, false);
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
    public ApiResponse<GoodsItem> goodsDetail(@PathVariable Long id) {
        return repository.findGoods(id)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("goods not found"));
    }

    @PostMapping("/orders")
    public ApiResponse<OrderItem> createOrder(
        @RequestHeader(value = "Authorization", required = false) String token,
        @RequestBody CreateOrderRequest request
    ) {
        try {
            Long userId = repository.findUserByToken(token).map(UserItem::id).orElseThrow(() -> new IllegalArgumentException("unauthorized"));
            return ApiResponse.ok(repository.createOrder(request, userId));
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
}
