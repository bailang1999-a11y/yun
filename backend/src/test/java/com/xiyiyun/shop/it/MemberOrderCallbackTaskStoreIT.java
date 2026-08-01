package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.MemberOrderCallbackPayload;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.MemberOrderCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.MemberOrderCallbackTaskEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "xiyiyun.member-callback.enabled=false")
class MemberOrderCallbackTaskStoreIT extends AbstractIntegrationTest {
    @Autowired
    private MemberOrderCallbackTaskStore taskStore;

    @Test
    void terminalTaskIsPersistentIdempotentAndLeaseRecoverable() {
        insertCredential("https://callback.example.com/orders");
        OrderItem order = order("ORDER-1", "external-1");
        String eventId = MemberOrderCallbackPayload.from(order, Instant.now()).orElseThrow().eventId();

        taskStore.registerTerminalOrder(order);
        taskStore.registerTerminalOrder(order);

        MemberOrderCallbackTaskEntity task = taskStore.findByEventId(eventId);
        assertThat(task).isNotNull();
        assertThat(task.getState()).isEqualTo("PENDING");
        assertThat(task.getCallbackUrl()).isEqualTo("https://callback.example.com/orders");
        assertThat(task.getPayloadJson()).contains("external-1").doesNotContain("13800138000");
        assertThat(task.getSensitiveCiphertext()).isNotEmpty();
        assertThat(taskStore.decryptSensitivePayload(task)).contains("13800138000", "PAY-1");
        task.setSensitiveKeyVersion("unsupported");
        assertThatThrownBy(() -> taskStore.decryptSensitivePayload(task))
            .isInstanceOf(IllegalArgumentException.class);
        task.setSensitiveKeyVersion("v1");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT LOCATE('13800138000', sensitive_ciphertext) FROM member_order_callback_tasks WHERE id = ?",
            Integer.class,
            task.getId()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM member_order_callback_tasks WHERE order_no = 'ORDER-1'", Integer.class
        )).isEqualTo(1);

        OffsetDateTime now = OffsetDateTime.now().plusSeconds(1);
        assertThat(taskStore.claim(task.getId(), now, now.plusSeconds(10))).isTrue();
        assertThat(taskStore.claim(task.getId(), now, now.plusSeconds(10))).isFalse();
        assertThat(taskStore.retry(task.getId(), now.plusSeconds(1), "temporary")).isTrue();
        assertThat(taskStore.claim(task.getId(), now.plusSeconds(2), now.plusSeconds(12))).isTrue();
        assertThat(taskStore.markSent(task.getId(), now.plusSeconds(2))).isTrue();
    }

    @Test
    void emptyMemberCallbackAndNativeAgisoTaskDoNotCreateGenericTasks() {
        insertCredential("");
        taskStore.registerTerminalOrder(order("ORDER-EMPTY", "external-empty"));
        assertThat(taskStore.findDue(OffsetDateTime.now().plusMinutes(1), 20)).isEmpty();

        jdbcTemplate.update("UPDATE member_api_credentials SET callback_url = ? WHERE user_id = 90001",
            "https://callback.example.com/orders");
        jdbcTemplate.update("""
            INSERT INTO agiso_callback_tasks (
                user_id, request_id, callback_url, goods_type, state, attempt_count, next_attempt_at
            ) VALUES (90001, 'external-native', 'https://mai.91kami.com/callback', 'DIRECT', 'PENDING', 0, NOW(3))
            """);
        taskStore.registerTerminalOrder(order("ORDER-NATIVE", "external-native"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM member_order_callback_tasks WHERE order_no = 'ORDER-NATIVE'", Integer.class
        )).isZero();
    }

    @Test
    void callbackConfigurationChangeCancelsOldAddressTasks() {
        insertCredential("https://old.example.com/orders");
        taskStore.registerTerminalOrder(order("ORDER-OLD", "external-old"));

        assertThat(taskStore.cancelPendingForUserExceptUrl(
            90001L, "https://new.example.com/orders", "callbackUrl changed"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM member_order_callback_tasks WHERE order_no = 'ORDER-OLD'", String.class
        )).isEqualTo("CANCELLED");
    }

    @Test
    void aSecondFailureAfterRetryCreatesANewEvent() {
        insertCredential("https://callback.example.com/orders");
        OffsetDateTime firstAt = OffsetDateTime.now();
        OrderItem first = order("ORDER-RETRY", "external-retry").withStatus(
            OrderStatus.FAILED, "first failure", firstAt
        );
        OrderItem second = first.withStatus(OrderStatus.FAILED, "second failure", firstAt.plusSeconds(1));

        taskStore.registerTerminalOrder(first);
        taskStore.registerTerminalOrder(first);
        taskStore.registerTerminalOrder(second);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM member_order_callback_tasks WHERE order_no = 'ORDER-RETRY'", Integer.class
        )).isEqualTo(2);
    }

    private void insertCredential(String callbackUrl) {
        jdbcTemplate.update("""
            INSERT INTO member_api_credentials (
                user_id, app_key, app_secret_masked, callback_url, status, ip_whitelist, daily_limit
            ) VALUES (90001, 'member_90001', '****', NULLIF(?, ''), 'ENABLED', JSON_ARRAY(), 1000)
            """, callbackUrl);
    }

    private OrderItem order(String orderNo, String requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            orderNo, 90001L, "buyer", 10004L, "测试商品", GoodsType.DIRECT,
            "api", "", "", 1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.DELIVERED,
            "13800138000", Map.of("account", "13800138000"), "", requestId,
            "PAY-1", "balance", List.of(), List.of(), "", now, now, now
        );
    }
}
