package com.xiyiyun.shop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.AdminProfile;
import java.util.Collection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisSecurityStateStoreTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedisSecurityStateStore store = new RedisSecurityStateStore(redisTemplate, objectMapper);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void memberApiNonceUsesRedisSetIfAbsent() {
        when(valueOperations.setIfAbsent(eq("xiyiyun:member-api:nonce:app:nonce"), eq("1"), any(Duration.class)))
            .thenReturn(true, false);

        assertThat(store.markMemberApiNonceReplay("app:nonce", Duration.ofMinutes(5))).contains(false);
        assertThat(store.markMemberApiNonceReplay("app:nonce", Duration.ofMinutes(5))).contains(true);
    }

    @Test
    void sliderTokenIsStoredAndConsumedOnce() {
        assertThat(store.storeSliderToken("slider_1", Duration.ofMinutes(5))).isTrue();
        verify(valueOperations).set("xiyiyun:slider:slider_1", "1", Duration.ofMinutes(5));

        when(redisTemplate.delete("xiyiyun:slider:slider_1")).thenReturn(true, false);

        assertThat(store.consumeSliderToken("slider_1")).contains(true);
        assertThat(store.consumeSliderToken("slider_1")).contains(false);
    }

    @Test
    void userTokenIsStoredLoadedAndInvalidatedByUserIndex() {
        assertThat(store.storeUserToken("h5_token", 90001L, Duration.ofDays(30))).isTrue();

        verify(valueOperations).set("xiyiyun:session:user:h5_token", "90001", Duration.ofDays(30));
        verify(setOperations).add("xiyiyun:session:user-index:90001", "h5_token");
        verify(redisTemplate).expire("xiyiyun:session:user-index:90001", Duration.ofDays(30));

        when(valueOperations.get("xiyiyun:session:user:h5_token")).thenReturn("90001");
        assertThat(store.loadUserToken("h5_token")).contains(90001L);

        store.deleteUserToken("h5_token");
        verify(redisTemplate).delete("xiyiyun:session:user:h5_token");
        verify(setOperations).remove("xiyiyun:session:user-index:90001", "h5_token");

        when(setOperations.members("xiyiyun:session:user-index:90001")).thenReturn(Set.of("h5_token", "h5_other"));
        store.invalidateUserTokens(90001L);

        ArgumentCaptor<Collection<String>> tokenKeysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(tokenKeysCaptor.capture());
        assertThat(tokenKeysCaptor.getValue())
            .containsExactlyInAnyOrder("xiyiyun:session:user:h5_token", "xiyiyun:session:user:h5_other");
        verify(redisTemplate).delete("xiyiyun:session:user-index:90001");
    }

    @Test
    void adminTokenIsStoredAsJsonAndLoaded() throws Exception {
        AdminProfile profile = new AdminProfile(1L, "admin", "运营管理员", List.of("dashboard:read"));

        assertThat(store.storeAdminToken("admin_token", profile, Duration.ofHours(12))).isTrue();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("xiyiyun:session:admin:admin_token"), jsonCaptor.capture(), eq(Duration.ofHours(12)));
        assertThat(objectMapper.readTree(jsonCaptor.getValue()).path("username").asText()).isEqualTo("admin");

        when(valueOperations.get("xiyiyun:session:admin:admin_token")).thenReturn(jsonCaptor.getValue());
        assertThat(store.loadAdminToken("admin_token")).contains(profile);

        store.deleteAdminToken("admin_token");
        verify(redisTemplate).delete("xiyiyun:session:admin:admin_token");
    }

    @Test
    void smsCodeIsStoredAsHashAndCanBeMatchedAfterLoad() throws Exception {
        OffsetDateTime sentAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = sentAt.plusMinutes(5);

        assertThat(store.storeSmsCode("USER_LOGIN:h5:13800000000", "123456", expiresAt, sentAt)).isTrue();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("xiyiyun:sms-code:USER_LOGIN:h5:13800000000"), jsonCaptor.capture(), any(Duration.class));
        JsonNode stored = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(stored.path("codeHash").asText()).isNotEqualTo("123456");
        assertThat(stored.path("attempts").asInt()).isZero();

        when(valueOperations.get("xiyiyun:sms-code:USER_LOGIN:h5:13800000000")).thenReturn(jsonCaptor.getValue());

        Optional<RedisSecurityStateStore.SmsCodeSnapshot> loaded = store.loadSmsCode("USER_LOGIN:h5:13800000000");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().found()).isTrue();
        assertThat(store.matchesSmsCode(loaded.get(), "123456")).isTrue();
        assertThat(store.matchesSmsCode(loaded.get(), "654321")).isFalse();
    }

    @Test
    void redisFailuresReturnEmptyForRepositoryFallback() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenThrow(new IllegalStateException("redis down"));
        when(redisTemplate.delete("xiyiyun:slider:slider_1")).thenThrow(new IllegalStateException("redis down"));

        assertThat(store.markMemberApiNonceReplay("app:nonce", Duration.ofMinutes(5))).isEmpty();
        assertThat(store.consumeSliderToken("slider_1")).isEmpty();
    }
}
