package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.persistence.AgisoCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgisoOrderCallbackServiceTest {
    private static final String CALLBACK_URL =
        "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback";
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String MEMBER_SECRET = "member-secret";

    private AgisoCallbackTaskStore taskStore;
    private AgisoCallbackClient callbackClient;
    private OutboundProtocolService protocolService;
    private AgisoOrderCallbackService service;

    @BeforeEach
    void setUp() {
        taskStore = mock(AgisoCallbackTaskStore.class);
        callbackClient = mock(AgisoCallbackClient.class);
        protocolService = mock(OutboundProtocolService.class);
        service = new AgisoOrderCallbackService(taskStore, callbackClient, protocolService, APP_SECRET);

        when(protocolService.callbackMemberSecret(90001L)).thenReturn(MEMBER_SECRET);
        when(protocolService.settings()).thenReturn(new OutboundProtocolSettings(
            true, "https://api.example.test", 100, 30, Map.of(), "ALL",
            List.of(), List.of(), List.of("CARD", "DIRECT"), "MEMBER_GROUP", BigDecimal.ZERO, false
        ));
    }

    @Test
    void terminalOrderIsBoundBeforeTheWorkerSignsDeliversAndMarksItSent() {
        AgisoCallbackTaskEntity task = task("PENDING", 0);
        OrderItem order = order(OrderStatus.DELIVERED);
        when(taskStore.registerPending(eq(90001L), eq("external-1"), eq(CALLBACK_URL), eq("DIRECT"), any()))
            .thenReturn(task);
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(protocolService.callbackOrder(90001L, "local-1", "external-1"))
            .thenReturn(Optional.of(order));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);

        service.prepare(90001L, "external-1", CALLBACK_URL, GoodsType.DIRECT);
        ArgumentCaptor<OffsetDateTime> preparedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(taskStore).registerPending(
            eq(90001L), eq("external-1"), eq(CALLBACK_URL), eq("DIRECT"), preparedAt.capture()
        );
        assertThat(Duration.between(OffsetDateTime.now(), preparedAt.getValue()).toMinutes()).isGreaterThanOrEqualTo(9);

        service.bind(90001L, "external-1", order);

        ArgumentCaptor<OffsetDateTime> releasedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(taskStore).bindOrder(eq(90001L), eq("external-1"), eq("local-1"), releasedAt.capture());
        assertThat(releasedAt.getValue()).isAfter(OffsetDateTime.now().plusSeconds(1));
        verify(callbackClient, never()).post(any(), any(), anyInt());

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(callbackClient).post(eq(CALLBACK_URL), payloadCaptor.capture(), eq(30));
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("orderNo", "external-1")
            .containsEntry("outTradeNo", "local-1")
            .containsEntry("orderStatus", 20)
            .containsEntry("cards", "");
        assertThat(payload.get("sign")).isEqualTo(
            AgisoSignatureUtil.sign(payload, APP_SECRET + MEMBER_SECRET)
        );
        verify(taskStore).markSent(eq(1L), any());
    }

    @Test
    void failedDirectOrderIsDeliveredAsFinalFailure() {
        AgisoCallbackTaskEntity task = task("PENDING", 0);
        OrderItem failed = order(OrderStatus.FAILED);
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(protocolService.callbackOrder(90001L, "local-1", "external-1"))
            .thenReturn(Optional.of(failed));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);

        service.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(callbackClient).post(eq(CALLBACK_URL), payloadCaptor.capture(), eq(30));
        assertThat(payloadCaptor.getValue())
            .containsEntry("orderStatus", 30)
            .containsEntry("failCode", 9999)
            .containsEntry("failReason", "done");
        assertThat(payloadCaptor.getValue().get("sign")).isEqualTo(
            AgisoSignatureUtil.sign(payloadCaptor.getValue(), APP_SECRET + MEMBER_SECRET)
        );
        verify(taskStore).markSent(eq(1L), any());
    }

    @Test
    void legacyInvalidFailCodeTasksAreRecoveredOnlyOncePerProcess() {
        when(taskStore.recoverInvalidFailCodeTasks(any())).thenReturn(2);
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of());

        service.dispatchDue();
        service.dispatchDue();

        verify(taskStore, times(1)).recoverInvalidFailCodeTasks(any());
    }

    @Test
    void pendingOrderIsDeferredWithoutSendingCallback() {
        AgisoCallbackTaskEntity task = task("PENDING", 0);
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(protocolService.callbackOrder(90001L, "local-1", "external-1"))
            .thenReturn(Optional.of(order(OrderStatus.PROCURING)));

        service.dispatchDue();

        verify(taskStore).deferPending(eq(1L), any());
        verify(callbackClient, never()).post(any(), any(), anyInt());
    }

    @Test
    void discardCannotKillAnAlreadyBoundCallbackTask() {
        AgisoCallbackTaskEntity task = task("PENDING", 0);
        when(taskStore.registerPending(eq(90001L), eq("external-1"), eq(CALLBACK_URL), eq("DIRECT"), any()))
            .thenReturn(task);

        service.prepare(90001L, "external-1", CALLBACK_URL, GoodsType.DIRECT);
        service.discard(90001L, "external-1", "duplicate request failed");

        verify(taskStore).markDeadIfUnbound(1L, "duplicate request failed");
        verify(taskStore, never()).markDead(eq(1L), any());
    }

    @Test
    void failedDeliveryIsPersistedForRetry() {
        AgisoCallbackTaskEntity task = task("PENDING", 2);
        when(taskStore.findDue(any(), anyInt())).thenReturn(List.of(task));
        when(protocolService.callbackOrder(90001L, "local-1", "external-1"))
            .thenReturn(Optional.of(order(OrderStatus.DELIVERED)));
        when(taskStore.claim(eq(1L), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary callback failure"))
            .when(callbackClient).post(eq(CALLBACK_URL), any(), eq(30));

        service.dispatchDue();

        verify(taskStore).retry(eq(1L), any(), eq("temporary callback failure"));
        verify(taskStore, never()).markSent(eq(1L), any());
    }

    private AgisoCallbackTaskEntity task(String state, int attempts) {
        AgisoCallbackTaskEntity task = new AgisoCallbackTaskEntity();
        task.setId(1L);
        task.setUserId(90001L);
        task.setRequestId("external-1");
        task.setOrderNo("local-1");
        task.setCallbackUrl(CALLBACK_URL);
        task.setGoodsType("DIRECT");
        task.setState(state);
        task.setAttemptCount(attempts);
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }

    private OrderItem order(OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "local-1", 90001L, "buyer", 10013L, "test recharge", GoodsType.DIRECT, "api",
            1, BigDecimal.ONE, BigDecimal.ONE, status, "", "", "external-1",
            "payment-1", "balance", List.of(), List.of(), "done",
            now, now, status == OrderStatus.DELIVERED ? now : null
        );
    }
}
