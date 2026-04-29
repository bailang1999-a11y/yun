package com.xiyiyun.shop;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
            "service", "xiyiyun-backend",
            "status", "UP",
            "time", OffsetDateTime.now().toString()
        ));
    }
}
