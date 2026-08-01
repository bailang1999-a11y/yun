package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CreateOrderRequest;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class OrderCreationRecoveryIT extends AbstractIntegrationTest {

    @Autowired
    private OrderRealtimeBroadcaster realtimeBroadcaster;

    @Test
    @DisplayName("同一 requestId 并发重试只创建一笔订单并扣一次库存")
    void concurrentRetriesReturnTheSameOrder() {
        seedManualGoods(10);
        CreateOrderRequest request = request("IT-SAME-REQUEST");

        List<ConcurrentRunner.Outcome<OrderItem>> outcomes = ConcurrentRunner.runAll(
            20,
            ignored -> () -> repository.createOrder(request, ItFixtures.USER_ID, "127.0.0.1", "h5")
        );

        assertThat(ConcurrentRunner.countSucceeded(outcomes)).isEqualTo(20);
        assertThat(outcomes.stream().map(ConcurrentRunner.Outcome::value).map(OrderItem::orderNo).distinct())
            .containsExactly(outcomes.getFirst().value().orderNo());
        assertThat(fixtures.countRows("orders")).isEqualTo(1);
        assertThat(fixtures.goodsStock(ItFixtures.GOODS_ID)).isEqualTo(9);
    }

    @Test
    @DisplayName("生产下单不等待 JVM 全局 orderLock")
    void persistentCreationDoesNotWaitForGlobalOrderLock() throws Exception {
        seedManualGoods(5);
        Object orderLock = orderLock();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> {
                synchronized (orderLock) {
                    locked.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();

            Future<OrderItem> created = executor.submit(() -> repository.createOrder(
                request("IT-NO-GLOBAL-LOCK"), ItFixtures.USER_ID, "127.0.0.1", "h5"
            ));

            assertThat(created.get(2, TimeUnit.SECONDS).requestId()).isEqualTo("IT-NO-GLOBAL-LOCK");
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("WebSocket 发送阻塞不影响创建订单响应")
    void slowRealtimeClientDoesNotBlockOrderCreation() throws Exception {
        seedManualGoods(5);
        WebSocketSession session = mock(WebSocketSession.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("role", "admin"));
        doAnswer(ignored -> {
            sendStarted.countDown();
            releaseSend.await(5, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        realtimeBroadcaster.addSession(session);

        try {
            long startedAt = System.nanoTime();
            OrderItem order = repository.createOrder(
                request("IT-SLOW-WEBSOCKET"), ItFixtures.USER_ID, "127.0.0.1", "h5"
            );
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(order.requestId()).isEqualTo("IT-SLOW-WEBSOCKET");
            assertThat(elapsedMillis).isLessThan(2_000L);
            assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseSend.countDown();
            realtimeBroadcaster.removeSession(session);
        }
    }

    private void seedManualGoods(int stock) {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("1000.0000"));
        fixtures.insertGoods("MANUAL", stock, new BigDecimal("5.0000"), null);
    }

    private CreateOrderRequest request(String requestId) {
        return new CreateOrderRequest(
            ItFixtures.GOODS_ID,
            1,
            null,
            "订单恢复测试",
            requestId,
            "h5",
            Map.of()
        );
    }

    private Object orderLock() throws ReflectiveOperationException {
        Field field = repository.getClass().getDeclaredField("orderLock");
        field.setAccessible(true);
        return field.get(repository);
    }
}
