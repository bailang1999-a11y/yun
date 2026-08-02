package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.persistence.WeComRobotDeliveryTaskStore;
import com.xiyiyun.shop.persistence.entity.WeComRobotDeliveryTaskEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeComRobotNotificationServiceTest {
    private static final String WEBHOOK =
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7abc-4bc4-97a0-0ec2aifa5aaa";

    private final WeComRobotDeliveryTaskStore taskStore = mock(WeComRobotDeliveryTaskStore.class);
    private final WeComRobotClient client = mock(WeComRobotClient.class);
    private final InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
    private final WeComRobotNotificationService service =
        new WeComRobotNotificationService(taskStore, client, repository);

    @BeforeEach
    void setUp() {
        when(repository.systemSetting()).thenReturn(systemSetting(true));
    }

    @Test
    void dueTaskIsClaimedSentAndMarkedSuccessful() {
        WeComRobotDeliveryTaskEntity task = task("DELIVERY_SUCCEEDED");
        when(taskStore.findDue(any(), eq(50))).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);

        service.dispatchDue();

        verify(client).sendMarkdown(WEBHOOK, "message");
        verify(taskStore).markSent(eq(1L), any());
        verify(taskStore, never()).retry(eq(1L), any(), any());
    }

    @Test
    void deliveryFailureIsPersistedForRetryWithoutEscapingWorker() {
        WeComRobotDeliveryTaskEntity task = task("DELIVERY_SUCCEEDED");
        when(taskStore.findDue(any(), eq(50))).thenReturn(List.of(task));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("network down"))
            .when(client).sendMarkdown(WEBHOOK, "message");

        service.dispatchDue();

        verify(taskStore).retry(eq(1L), any(), eq("network down"));
        verify(taskStore, never()).markSent(eq(1L), any());
    }

    @Test
    void testSendReportsRemoteFailureToTheAdmin() {
        WeComRobotDeliveryTaskEntity task = task("TEST");
        when(taskStore.registerTest()).thenReturn(task);
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("invalid webhook"))
            .when(client).sendMarkdown(WEBHOOK, "message");

        assertThatThrownBy(service::sendTest)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid webhook");
        verify(taskStore).retry(eq(1L), any(), eq("invalid webhook"));
    }

    private WeComRobotDeliveryTaskEntity task(String eventType) {
        WeComRobotDeliveryTaskEntity task = new WeComRobotDeliveryTaskEntity();
        task.setId(1L);
        task.setEventType(eventType);
        task.setOrderNo("ORDER-1");
        task.setMarkdownContent("message");
        task.setState("PENDING");
        task.setAttemptCount(0);
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }

    private SystemSettingItem systemSetting(boolean enabled) {
        return new SystemSettingItem(
            "喜易云", "", "", "", "", "", "", "MOCK", true, "TENCENT", false,
            30, true, false, true, "MOBILE", 1L, Map.of(),
            new WeComRobotSetting(
                enabled, enabled ? WEBHOOK : "", List.of(WeComNotificationEvent.DELIVERY_SUCCEEDED)
            )
        );
    }
}
