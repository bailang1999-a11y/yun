package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CreateOrderRequest;
import com.xiyiyun.shop.mvp.OrderItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发用例 2：stock=1 时并发下单。
 *
 * <p><b>预期红灯，暴露缺陷：下单不扣库存。</b>
 * {@code InMemoryShopRepository.createOrder}（约 3622-3625 行）只做
 * {@code refreshStock(item)} 读取 + {@code stock < quantity} 校验，
 * <b>校验通过后从不递减库存</b>，也没有把「查库存 → 判断 → 扣减」放进任何原子操作里。
 * 于是 N 个线程都能通过同一份 stock=1 的校验，全部建单成功。
 *
 * <p>商品类型选 MANUAL：避免 DIRECT 触发对外部供应商的 HTTP 采购调用。
 * MANUAL 商品的库存直接取 {@code goods.stock_count}，不会被 refreshStock 按卡密重算，
 * 因此断言对象干净：库存只能因为「下单扣减」而变化。
 */
class ConcurrentStockDeductionIT extends AbstractIntegrationTest {

    private static final int THREADS = 20;

    @Test
    @DisplayName("stock=1 并发下单：只应 1 单成功且库存不能为负")
    void onlyOneOrderMaySucceedWhenStockIsOne() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("100000.0000"));
        fixtures.insertGoods("MANUAL", 1, new BigDecimal("5.0000"), null);

        List<ConcurrentRunner.Outcome<OrderItem>> outcomes = ConcurrentRunner.runAll(THREADS, index -> () -> {
            CreateOrderRequest request = new CreateOrderRequest(
                ItFixtures.GOODS_ID, 1, null, "并发库存用例",
                "IT-STOCK-" + index, "h5", Map.of());
            return repository.createOrder(request, ItFixtures.USER_ID, "127.0.0.1", "h5");
        });

        long succeeded = ConcurrentRunner.countSucceeded(outcomes);
        int persistedOrders = fixtures.countRows("orders");
        Integer stockAfter = fixtures.goodsStock(ItFixtures.GOODS_ID);

        assertThat(stockAfter)
            .as("库存任何时候都不能为负（实际=%s）", stockAfter)
            .isGreaterThanOrEqualTo(0);

        assertThat(succeeded)
            .as("""
                库存只有 1，%d 个线程并发下单必须恰好 1 个成功。
                实际成功=%d，落库订单数=%d，商品剩余库存=%s。
                失败线程原因样本=%s""",
                THREADS, succeeded, persistedOrders, stockAfter,
                ConcurrentRunner.failureMessages(outcomes).stream().distinct().limit(3).toList())
            .isEqualTo(1);

        assertThat(persistedOrders)
            .as("库存 1 只允许 1 条订单落库，实际落库 %d 条（超卖）", persistedOrders)
            .isEqualTo(1);

        assertThat(stockAfter)
            .as("成功 1 单后库存应从 1 扣到 0，实际=%s（说明下单根本没有扣减库存）", stockAfter)
            .isEqualTo(0);
    }
}
