package com.xiyiyun.shop.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.AdminProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSecurityStateStore {
    private static final Logger log = LoggerFactory.getLogger(RedisSecurityStateStore.class);
    private static final String MEMBER_API_NONCE_PREFIX = "xiyiyun:member-api:nonce:";
    private static final String SLIDER_TOKEN_PREFIX = "xiyiyun:slider:";
    private static final String SMS_CODE_PREFIX = "xiyiyun:sms-code:";
    private static final String USER_TOKEN_PREFIX = "xiyiyun:session:user:";
    private static final String USER_TOKEN_INDEX_PREFIX = "xiyiyun:session:user-index:";
    private static final String ADMIN_TOKEN_PREFIX = "xiyiyun:session:admin:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSecurityStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<Boolean> markMemberApiNonceReplay(String nonceKey, Duration ttl) {
        try {
            Boolean inserted = redisTemplate.opsForValue()
                .setIfAbsent(MEMBER_API_NONCE_PREFIX + nonceKey, "1", ttl);
            return Optional.of(!Boolean.TRUE.equals(inserted));
        } catch (RuntimeException ex) {
            log.warn("Redis member API nonce check failed, falling back to local memory: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean storeUserToken(String token, Long userId, Duration ttl) {
        if (userId == null) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(USER_TOKEN_PREFIX + token, String.valueOf(userId), ttl);
            String indexKey = USER_TOKEN_INDEX_PREFIX + userId;
            redisTemplate.opsForSet().add(indexKey, token);
            redisTemplate.expire(indexKey, ttl);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Redis user token store failed, falling back to local memory: {}", ex.getMessage());
            return false;
        }
    }

    public Optional<Long> loadUserToken(String token) {
        try {
            String raw = redisTemplate.opsForValue().get(USER_TOKEN_PREFIX + token);
            return raw == null ? Optional.empty() : Optional.of(Long.parseLong(raw));
        } catch (RuntimeException ex) {
            log.warn("Redis user token load failed, falling back to local memory: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void deleteUserToken(String token) {
        try {
            Optional<Long> userId = loadUserToken(token);
            redisTemplate.delete(USER_TOKEN_PREFIX + token);
            userId.ifPresent(id -> redisTemplate.opsForSet().remove(USER_TOKEN_INDEX_PREFIX + id, token));
        } catch (RuntimeException ex) {
            log.warn("Redis user token delete failed: {}", ex.getMessage());
        }
    }

    public void deleteAdminToken(String token) {
        try {
            redisTemplate.delete(ADMIN_TOKEN_PREFIX + token);
        } catch (RuntimeException ex) {
            log.warn("Redis admin token delete failed: {}", ex.getMessage());
        }
    }

    public void invalidateUserTokens(Long userId) {
        if (userId == null) {
            return;
        }
        String indexKey = USER_TOKEN_INDEX_PREFIX + userId;
        try {
            Set<String> tokens = redisTemplate.opsForSet().members(indexKey);
            if (tokens != null && !tokens.isEmpty()) {
                List<String> tokenKeys = tokens.stream().map(token -> USER_TOKEN_PREFIX + token).toList();
                redisTemplate.delete(tokenKeys);
            }
            redisTemplate.delete(indexKey);
        } catch (RuntimeException ex) {
            log.warn("Redis user token invalidation failed: {}", ex.getMessage());
        }
    }

    public boolean storeAdminToken(String token, AdminProfile profile, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(ADMIN_TOKEN_PREFIX + token, objectMapper.writeValueAsString(profile), ttl);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis admin token store failed, falling back to local memory: {}", ex.getMessage());
            return false;
        }
    }

    public Optional<AdminProfile> loadAdminToken(String token) {
        try {
            String json = redisTemplate.opsForValue().get(ADMIN_TOKEN_PREFIX + token);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, AdminProfile.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis admin token load failed, falling back to local memory: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean storeSliderToken(String token, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(SLIDER_TOKEN_PREFIX + token, "1", ttl);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Redis slider token store failed, falling back to local memory: {}", ex.getMessage());
            return false;
        }
    }

    public Optional<Boolean> consumeSliderToken(String token) {
        try {
            return Optional.of(Boolean.TRUE.equals(redisTemplate.delete(SLIDER_TOKEN_PREFIX + token)));
        } catch (RuntimeException ex) {
            log.warn("Redis slider token consume failed, falling back to local memory: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<SmsCodeSnapshot> loadSmsCode(String key) {
        try {
            String json = redisTemplate.opsForValue().get(SMS_CODE_PREFIX + key);
            if (json == null) {
                return Optional.of(SmsCodeSnapshot.missing(key));
            }
            StoredSmsCode stored = objectMapper.readValue(json, StoredSmsCode.class);
            return Optional.of(new SmsCodeSnapshot(
                key,
                stored.codeHash(),
                stored.expiresAt(),
                stored.sentAt(),
                stored.attempts(),
                stored.used(),
                true
            ));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis sms code load failed, falling back to local memory: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean storeSmsCode(String key, String code, OffsetDateTime expiresAt, OffsetDateTime sentAt) {
        return storeSmsCode(new SmsCodeSnapshot(key, hashCode(code), expiresAt, sentAt, 0, false, true));
    }

    public boolean storeSmsCode(SmsCodeSnapshot snapshot) {
        if (!snapshot.found() || snapshot.expiresAt() == null) {
            return false;
        }
        Duration ttl = Duration.between(OffsetDateTime.now(), snapshot.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            deleteSmsCode(snapshot.key());
            return true;
        }
        try {
            StoredSmsCode stored = new StoredSmsCode(
                snapshot.codeHash(),
                snapshot.expiresAt(),
                snapshot.sentAt(),
                snapshot.attempts(),
                snapshot.used()
            );
            redisTemplate.opsForValue().set(SMS_CODE_PREFIX + snapshot.key(), objectMapper.writeValueAsString(stored), ttl);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis sms code store failed, falling back to local memory: {}", ex.getMessage());
            return false;
        }
    }

    public void deleteSmsCode(String key) {
        try {
            redisTemplate.delete(SMS_CODE_PREFIX + key);
        } catch (RuntimeException ex) {
            log.warn("Redis sms code delete failed: {}", ex.getMessage());
        }
    }

    public boolean matchesSmsCode(SmsCodeSnapshot snapshot, String code) {
        return snapshot != null && snapshot.found() && snapshot.codeHash().equals(hashCode(code));
    }

    private String hashCode(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(defaultText(code).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash sms code", ex);
        }
    }

    private String defaultText(String value) {
        return value == null ? "" : value.trim();
    }

    public record SmsCodeSnapshot(
        String key,
        String codeHash,
        OffsetDateTime expiresAt,
        OffsetDateTime sentAt,
        int attempts,
        boolean used,
        boolean found
    ) {
        public static SmsCodeSnapshot missing(String key) {
            return new SmsCodeSnapshot(key, "", null, null, 0, false, false);
        }

        public SmsCodeSnapshot withAttempts(int nextAttempts) {
            return new SmsCodeSnapshot(key, codeHash, expiresAt, sentAt, nextAttempts, used, found);
        }

        public SmsCodeSnapshot markUsed(int nextAttempts) {
            return new SmsCodeSnapshot(key, codeHash, expiresAt, sentAt, nextAttempts, true, found);
        }
    }

    private record StoredSmsCode(
        String codeHash,
        OffsetDateTime expiresAt,
        OffsetDateTime sentAt,
        int attempts,
        boolean used
    ) {
    }
}
