package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment")
public class PaymentMvpController {
    private final InMemoryShopRepository repository;

    public PaymentMvpController(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/callback/{provider}")
    public ApiResponse<OrderItem> callback(@PathVariable String provider, @RequestBody PaymentCallbackRequest request) {
        try {
            return ApiResponse.ok(repository.handlePaymentCallback(provider, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }
}
