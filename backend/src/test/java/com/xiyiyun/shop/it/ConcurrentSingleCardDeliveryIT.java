package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 并发用例 1：50 线程抢 1 张卡。
 *
 * <p>被测路径是 {@link PersistentOrderStore#deliverCardsForOrder}：
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} 取卡 + {@code UPDATE ... WHERE status='UNSOLD'} 条件更新，
 * 是当前代码里唯一正确的原子发卡实现。
 *
 * <p>刻意<b>绕开</b> {@code InMemoryShopRepository} 的 {@code synchronized (orderLock)}：
 * 直接从 50 个线程调 store，让竞争真正落到数据库行锁上。如果这里绿，说明测试基座
 * （真库 + 真并发 + 连接池够大）本身是可信的，后面用例红灯就不能推给"测试写错了"。
 *
 * <p><b>预期绿灯。</b>
 */
class ConcurrentSingleCardDeliveryIT extends AbstractIntegrationTest {

    private static final int THREADS = 50;

    @Autowired
    private PersistentOrderStore persistentOrderStore;

    @Test
    @DisplayName("50 线程并发发同一张卡：恰好 1 成功 49 失败，卡不会被卖两次")
    void onlyOneThreadCanSellTheSingleCard() {
        fixtures.seedCardGoodsScenario(new BigDecimal("1000.0000"), 1, new BigDecimal("9.9000"));
        long cardId = fixtures.insertUnsoldCard("IT-SINGLE-CARD-0001");

        // 50 个各自独立的订单快照，每个都想要 1 张卡
        List<OrderItem> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String orderNo = "ITSINGLE" + String.format("%04d", i);
            fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
                OrderStatus.PAID.name(), 1, new BigDecimal("9.9000"));
            orders.add(order(orderNo));
        }

        List<ConcurrentRunner.Outcome<List<String>>> outcomes = ConcurrentRunner.runAll(THREADS,
            index -> () -> persistentOrderStore.deliverCardsForOrder(orders.get(index), ItFixtures.CARD_KIND_ID));

        long succeeded = ConcurrentRunner.countSucceeded(outcomes);

        assertThat(succeeded)
            .as("只有 1 张 UNSOLD 卡，%d 个线程里必须恰好 1 个成功；失败原因样本=%s",
                THREADS, ConcurrentRunner.failureMessages(outcomes).stream().distinct().limit(3).toList())
            .isEqualTo(1);
        assertThat(THREADS - succeeded).as("其余线程必须全部失败").isEqualTo(THREADS - 1);

        // 卡状态：SOLD，且只被一个订单占用
        List<Map<String, Object>> cards = fixtures.query(
            "SELECT id, status, sold_order_id, sold_at FROM cards WHERE id = ?", cardId);
        assertThat(cards).hasSize(1);
        assertThat(String.valueOf(cards.get(0).get("status"))).isEqualTo("SOLD");
        Object soldOrderId = cards.get(0).get("sold_order_id");
        assertThat(soldOrderId).as("SOLD 卡必须绑定唯一 sold_order_id").isNotNull();

        // 全库不允许出现「一卡两单」
        assertThat(fixtures.countCardsByStatus("SOLD")).as("被卖出的卡只能有 1 张").isEqualTo(1);
        assertThat(fixtures.countCardsByStatus("UNSOLD")).as("不应还剩 UNSOLD 卡").isEqualTo(0);
        List<Map<String, Object>> duplicateSold = fixtures.query("""
            SELECT sold_order_id, COUNT(*) AS c FROM cards
            WHERE sold_order_id IS NOT NULL GROUP BY sold_order_id HAVING COUNT(*) > 1
            """);
        assertThat(duplicateSold).as("同一订单不应占用多张卡").isEmpty();

        // 成功线程拿到的明文必须是 fixture 原文（验证密文/nonce/密钥链路正确）
        String delivered = outcomes.stream()
            .filter(ConcurrentRunner.Outcome::succeeded)
            .flatMap(outcome -> outcome.value().stream())
            .findFirst()
            .orElseThrow();
        assertThat(delivered).isEqualTo("IT-SINGLE-CARD-0001");
    }

    private OrderItem order(String orderNo) {
        return new OrderItem(
            orderNo,
            ItFixtures.USER_ID,
            "IT用户",
            ItFixtures.GOODS_ID,
            "IT测试商品",
            GoodsType.CARD,
            "h5",
            null,
            null,
            1,
            new BigDecimal("9.9000"),
            new BigDecimal("9.9000"),
            OrderStatus.PAID,
            null,
            Map.of(),
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            "",
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            null);
    }
}
