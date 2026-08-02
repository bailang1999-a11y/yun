package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeComRobotMessageFactoryTest {
    @Test
    void completeOrderMessageIncludesBusinessDataButNeverCardSecrets() {
        OrderItem order = order(OrderStatus.DELIVERED, List.of(), List.of("CARD-NO:SECRET-PASSWORD"));

        WeComRobotMessageFactory.Notification notification = WeComRobotMessageFactory.from(order).get(0);

        assertThat(notification.event()).isEqualTo(WeComNotificationEvent.DELIVERY_SUCCEEDED);
        assertThat(notification.markdown())
            .contains("13800138000", "外部-1001", "上游-2001", "¥9.9", "大区=华东")
            .contains("卡密正文不发送到群")
            .doesNotContain("SECRET-PASSWORD", "CARD-NO");
        assertThat(notification.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(3900);
    }

    @Test
    void eventIdIsStableForTheSameOrderEvent() {
        OrderItem order = order(OrderStatus.DELIVERED, List.of(), List.of("secret"));

        assertThat(WeComRobotMessageFactory.from(order).get(0).eventId())
            .isEqualTo(WeComRobotMessageFactory.from(order).get(0).eventId());
    }

    @Test
    void directCompletionCreatesPaymentAndDeliveryEventsWithoutAPaidSnapshot() {
        List<WeComRobotMessageFactory.Notification> notifications = WeComRobotMessageFactory.from(
            order(OrderStatus.DELIVERED, List.of(), List.of())
        );

        assertThat(notifications).extracting(WeComRobotMessageFactory.Notification::event)
            .containsExactly(
                WeComNotificationEvent.DELIVERY_SUCCEEDED,
                WeComNotificationEvent.PAYMENT_SUCCEEDED
            );
    }

    @Test
    void nonTerminalFailedChannelCreatesUpstreamException() {
        ChannelAttemptItem failed = new ChannelAttemptItem(
            1L, 2L, "上游", "SKU-1", 1, "FAILED", "连接超时", OffsetDateTime.now()
        );

        List<WeComRobotMessageFactory.Notification> notifications = WeComRobotMessageFactory.from(
            order(OrderStatus.PROCURING, List.of(failed), List.of())
        );

        assertThat(notifications).extracting(WeComRobotMessageFactory.Notification::event)
            .containsExactly(
                WeComNotificationEvent.PAYMENT_SUCCEEDED,
                WeComNotificationEvent.UPSTREAM_EXCEPTION
            );
        assertThat(notifications)
            .filteredOn(item -> item.event() == WeComNotificationEvent.UPSTREAM_EXCEPTION)
            .singleElement()
            .extracting(WeComRobotMessageFactory.Notification::markdown)
            .asString()
            .contains("连接超时");
    }

    private OrderItem order(
        OrderStatus status,
        List<ChannelAttemptItem> attempts,
        List<String> deliveryItems
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-02T12:00:00+08:00");
        return new OrderItem(
            "本地-1001", 9001L, "buyer@example.com", 10004L, "测试卡密商品", GoodsType.CARD,
            "api", "127.0.0.1", "本地", 1, new BigDecimal("8.80"), new BigDecimal("8.80"),
            status, "13800138000", Map.of("大区", "华东"), "备注", "外部-1001", "PAY-1",
            "BALANCE", deliveryItems, attempts, "", now, now.plusSeconds(1), now.plusSeconds(2),
            "上游-2001", new BigDecimal("9.90")
        );
    }
}
