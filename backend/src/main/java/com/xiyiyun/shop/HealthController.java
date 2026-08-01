package com.xiyiyun.shop;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/health/live")
    public ApiResponse<Map<String, Object>> liveness() {
        return ApiResponse.ok(status("UP"));
    }

    @GetMapping("/api/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        try {
            Integer database = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            String redis;
            try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                redis = connection.ping();
            }
            if (!Integer.valueOf(1).equals(database) || !"PONG".equalsIgnoreCase(redis)) {
                throw new IllegalStateException("dependency check failed");
            }
            return ResponseEntity.ok(ApiResponse.ok(status("UP")));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail("service dependencies unavailable"));
        }
    }

    private Map<String, Object> status(String status) {
        return Map.of(
            "service", "xiyiyun-backend",
            "status", status,
            "time", OffsetDateTime.now().toString()
        );
    }
}
