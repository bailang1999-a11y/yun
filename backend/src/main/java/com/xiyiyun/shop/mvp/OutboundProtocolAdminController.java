package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/outbound-protocols")
public class OutboundProtocolAdminController {
    private final OutboundProtocolService service;

    public OutboundProtocolAdminController(OutboundProtocolService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<OutboundProtocolSettings> settings() {
        return ApiResponse.ok(service.settings());
    }

    @PostMapping
    public ApiResponse<OutboundProtocolSettings> save(@RequestBody OutboundProtocolSettingsRequest request) {
        return ApiResponse.ok(service.save(request));
    }
}
