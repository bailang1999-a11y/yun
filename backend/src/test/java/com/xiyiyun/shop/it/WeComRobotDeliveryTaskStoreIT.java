package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.WeComRobotDeliveryTaskStore;
import com.xiyiyun.shop.persistence.entity.WeComRobotDeliveryTaskEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "xiyiyun.wecom-robot.enabled=false")
class WeComRobotDeliveryTaskStoreIT extends AbstractIntegrationTest {
    @Autowired
    private WeComRobotDeliveryTaskStore taskStore;

    @AfterEach
    void clearSettings() {
        jdbcTemplate.update("DELETE FROM system_settings WHERE setting_key LIKE 'wecom.robot.%'");
    }

    @Test
    void selectedOrderEventIsPersistentIdempotentAndLeaseRecoverable() {
        saveSetting("wecom.robot.enabled", "true");
        saveSetting("wecom.robot.events", "DELIVERY_SUCCEEDED");
        OrderItem order = deliveredOrder();

        taskStore.registerOrderEvents(order);
        taskStore.registerOrderEvents(order);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wecom_robot_delivery_tasks WHERE order_no = ?", Integer.class, order.orderNo()
        )).isEqualTo(1);
        WeComRobotDeliveryTaskEntity task = taskStore.latest(1).get(0);
        assertThat(task.getEventType()).isEqualTo("DELIVERY_SUCCEEDED");
        assertThat(task.getMarkdownContent())
            .contains("13800138000", "外部-1")
            .doesNotContain("CARD-SECRET");

        OffsetDateTime now = OffsetDateTime.now().plusSeconds(1);
        assertThat(taskStore.claim(task.getId(), now, now.plusSeconds(10))).isTrue();
        assertThat(taskStore.claim(task.getId(), now, now.plusSeconds(10))).isFalse();
        assertThat(taskStore.retry(task.getId(), now.plusSeconds(1), "temporary")).isTrue();
        assertThat(taskStore.claim(task.getId(), now.plusSeconds(2), now.plusSeconds(12))).isTrue();
        assertThat(taskStore.markSent(task.getId(), now.plusSeconds(3))).isTrue();
    }

    @Test
    void disabledOrUnselectedEventsDoNotCreateTasks() {
        saveSetting("wecom.robot.enabled", "false");
        saveSetting("wecom.robot.events", "DELIVERY_SUCCEEDED");
        taskStore.registerOrderEvents(deliveredOrder());
        assertThat(taskStore.latest(10)).isEmpty();

        saveSetting("wecom.robot.enabled", "true");
        saveSetting("wecom.robot.events", "ORDER_CREATED");
        taskStore.registerOrderEvents(deliveredOrder());
        assertThat(taskStore.latest(10)).isEmpty();
    }

    @Test
    void testTaskCanBeRegisteredWithoutAnOrder() {
        WeComRobotDeliveryTaskEntity task = taskStore.registerTest();

        assertThat(task.getId()).isNotNull();
        assertThat(task.getEventType()).isEqualTo("TEST");
        assertThat(task.getOrderNo()).isNull();
        assertThat(task.getMarkdownContent()).contains("测试通知");
    }

    private void saveSetting(String key, String value) {
        jdbcTemplate.update("""
            INSERT INTO system_settings (setting_key, setting_value) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
            """, key, value);
    }

    private OrderItem deliveredOrder() {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "ORDER-WECOM-1", 90001L, "buyer", 10004L, "测试商品", GoodsType.CARD,
            "api", "", "", 1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.DELIVERED,
            "13800138000", Map.of("account", "13800138000"), "", "外部-1",
            "PAY-1", "balance", List.of("CARD-SECRET"), List.of(), "", now, now, now,
            "UPSTREAM-1", new BigDecimal("1.20")
        );
    }
}
