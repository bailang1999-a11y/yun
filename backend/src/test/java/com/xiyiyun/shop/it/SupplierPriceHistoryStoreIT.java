package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.ChannelAttemptItem;
import com.xiyiyun.shop.mvp.GoodsChannelItem;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.SupplierPriceTrendItem;
import com.xiyiyun.shop.persistence.SupplierPriceHistoryStore;
import com.xiyiyun.shop.persistence.mapper.SupplierPriceHistoryMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class SupplierPriceHistoryStoreIT extends AbstractIntegrationTest {
    private static final long GOODS_ID = 8301L;
    private static final long CHANNEL_ID = 8401L;

    @Autowired
    private SupplierPriceHistoryStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void recordsOnlyRealChangesAndReturnsLatestTenForUsedOrPrimaryChannel() {
        OffsetDateTime base = OffsetDateTime.parse("2026-08-01T10:00:00+08:00");
        GoodsChannelItem channel = new GoodsChannelItem(
            CHANNEL_ID, GOODS_ID, 8501L, "IT上游", "REMOTE-8301", 1, 30, "ENABLED", base
        );
        jdbcTemplate.update("""
            INSERT INTO goods_channels (
                id, goods_id, supplier_id, supplier_name, supplier_goods_id,
                priority, timeout_seconds, status, created_at
            ) VALUES (?, ?, ?, ?, ?, 1, 30, 'ENABLED', ?)
            """, CHANNEL_ID, GOODS_ID, 8501L, "IT上游", "REMOTE-8301", base);

        for (int index = 1; index <= 12; index++) {
            store.record(channel, BigDecimal.valueOf(index), base.plusMinutes(index));
        }
        store.record(channel, BigDecimal.valueOf(12), base.plusMinutes(20));

        OrderItem usedChannelOrder = order(
            "PRICE-TREND-USED",
            List.of(new ChannelAttemptItem(
                CHANNEL_ID, 8501L, "IT上游", "REMOTE-8301", 1, "SUCCESS", "ok", base.plusMinutes(12)
            ))
        );
        OrderItem primaryChannelOrder = order("PRICE-TREND-PRIMARY", List.of());
        List<OrderItem> enriched = store.attachRecentTrends(List.of(usedChannelOrder, primaryChannelOrder));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM supplier_price_history WHERE channel_id = ?", Integer.class, CHANNEL_ID
        )).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT direction FROM supplier_price_history WHERE channel_id = ? ORDER BY id LIMIT 1",
            String.class,
            CHANNEL_ID
        )).isEqualTo("INITIAL");
        assertThat(enriched).allSatisfy(item -> {
            SupplierPriceTrendItem trend = item.supplierPriceTrend();
            assertThat(trend).isNotNull();
            assertThat(trend.channelId()).isEqualTo(CHANNEL_ID);
            assertThat(trend.latestUnitPrice()).isEqualByComparingTo("12.0000");
            assertThat(trend.latestDirection()).isEqualTo("UP");
            assertThat(trend.points()).hasSize(10);
            assertThat(trend.points().getFirst().unitPrice()).isEqualByComparingTo("3.0000");
            assertThat(trend.points().getLast().unitPrice()).isEqualByComparingTo("12.0000");
            assertThat(trend.points().getLast().changeAmount()).isEqualByComparingTo("1.0000");
        });

        store.record(channel, BigDecimal.valueOf(11), base.plusMinutes(21));
        SupplierPriceTrendItem downward = store.attachRecentTrends(List.of(primaryChannelOrder))
            .getFirst()
            .supplierPriceTrend();
        assertThat(downward.latestDirection()).isEqualTo("DOWN");
        assertThat(downward.points().getLast().changeAmount()).isEqualByComparingTo("-1.0000");
    }

    @Test
    void concurrentSamePriceObservationsProduceOnlyOneBaseline() throws Exception {
        long channelId = CHANNEL_ID + 1;
        OffsetDateTime now = OffsetDateTime.parse("2026-08-01T12:00:00+08:00");
        GoodsChannelItem channel = new GoodsChannelItem(
            channelId, GOODS_ID, 8501L, "IT上游", "REMOTE-CONCURRENT", 2, 30, "ENABLED", now
        );
        jdbcTemplate.update("""
            INSERT INTO goods_channels (
                id, goods_id, supplier_id, supplier_name, supplier_goods_id,
                priority, timeout_seconds, status, created_at
            ) VALUES (?, ?, ?, ?, ?, 2, 30, 'ENABLED', ?)
            """, channelId, GOODS_ID, 8501L, "IT上游", "REMOTE-CONCURRENT", now);
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = java.util.stream.IntStream.range(0, workers)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.record(channel, new BigDecimal("9.9900"), now.plusSeconds(index));
                    return null;
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM supplier_price_history WHERE channel_id = ?", Integer.class, channelId
        )).isEqualTo(1);
    }

    @Test
    void trendQueryFailureDoesNotMarkTheOrderTransactionRollbackOnly() {
        SupplierPriceHistoryMapper failingMapper = mock(SupplierPriceHistoryMapper.class);
        when(failingMapper.selectRecentByChannelIds(List.of(CHANNEL_ID)))
            .thenThrow(new IllegalStateException("history table unavailable"));
        ProxyFactory proxyFactory = new ProxyFactory(new SupplierPriceHistoryStore(failingMapper));
        proxyFactory.addAdvice(new TransactionInterceptor(
            transactionManager,
            new AnnotationTransactionAttributeSource()
        ));
        SupplierPriceHistoryStore isolatedStore = (SupplierPriceHistoryStore) proxyFactory.getProxy();
        OrderItem order = order(
            "PRICE-TREND-FALLBACK",
            List.of(new ChannelAttemptItem(
                CHANNEL_ID, 8501L, "IT上游", "REMOTE-8301", 1, "SUCCESS", "ok", OffsetDateTime.now()
            ))
        );

        assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            try {
                isolatedStore.attachRecentTrends(List.of(order));
            } catch (RuntimeException expected) {
                // 订单分页会降级返回无趋势数据；这里验证异常不会污染它的外层事务。
            }
            assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        })).doesNotThrowAnyException();
    }

    private OrderItem order(String orderNo, List<ChannelAttemptItem> attempts) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-01T11:00:00+08:00");
        return new OrderItem(
            orderNo, null, "", GOODS_ID, "IT趋势商品", GoodsType.DIRECT, "api", "", "",
            1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.DELIVERED, "", Map.of(), "", orderNo,
            "", "", List.of(), attempts, "", now, now, now, "", null
        );
    }
}
