package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CreateOrderRequest;
import com.xiyiyun.shop.mvp.PayOrderRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发用例 5：同一订单并发施加互斥状态变更（支付成功 vs 取消）。
 *
 * <p><b>预期红灯，暴露缺陷：状态机只在内存里校验，且校验与写入不在同一把锁下。</b>
 * {@code mvp/OrderStateMachine}（77 行）全是 static 的内存断言；
 * {@code payOrder} 在 {@code synchronized (orderLock)} 内校验并推进状态，
 * 而 {@code cancelOrder} 是 {@code public synchronized}（锁 {@code this}）。
 * 两把不同的锁 + 数据库侧没有任何「状态条件更新」（形如
 * {@code UPDATE orders SET status='CANCELLED' WHERE status='UNPAID'}），
 * 使得「校验时是 UNPAID → 写入时已被别人改掉」的窗口真实存在：
 * 一笔订单可能既被扣款发货、又被标记取消。
 *
 * <p>断言分两层：终态必须合法，且不允许出现自相矛盾的组合
 * （已取消却扣了钱 / 已取消却发了卡 / 既退款又发货）。
 */
class ConcurrentOrderStateTransitionIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 12;                                 // 12 笔订单各来一次 pay/cancel 对撞
    private static final BigDecimal PRICE = new BigDecimal("6.0000");
    private static final BigDecimal INITIAL = new BigDecimal("100000.0000");

    private static final Set<String> LEGAL_TERMINAL_STATES = Set.of(
        OrderStatus.DELIVERED.name(),
        OrderStatus.PAID.name(),
        OrderStatus.FAILED.name(),
        OrderStatus.CANCELLED.name());

    @Test
    @DisplayName("支付与取消同时打同一订单：终态合法，且不得出现「已取消却已扣款/已发卡」")
    void concurrentPayAndCancelMustNotProduceContradictoryState() {
        fixtures.seedCardGoodsScenario(INITIAL, ROUNDS, PRICE);
        for (int i = 0; i < ROUNDS; i++) {
            fixtures.insertUnsoldCard("IT-STATE-CARD-" + String.format("%04d", i));
        }

        // 订单直接预置成 UNPAID 落库，不走 createOrder。
        // 原因：CARD 类商品的 createOrder 会被 refreshStock 拦住（缺陷 A2，库里有卡但内存
        // cards Map 为空导致可售数算成 0），那是 OrderFullChainIT 负责暴露的缺陷。
        // 本用例要测的是「支付与取消并发时的状态机互斥」，必须绕开该遮挡才能触达自己的断言。
        List<String> orderNos = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            String orderNo = String.format("ITSTATE%04d", i);
            fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
                "UNPAID", 1, PRICE);
            orderNos.add(orderNo);
        }

        BigDecimal balanceBefore = fixtures.userBalance(ItFixtures.USER_ID);

        // 2*ROUNDS 个线程同时放开：偶数号支付第 i 单，奇数号取消第 i 单
        List<ConcurrentRunner.Outcome<String>> outcomes = ConcurrentRunner.runAll(ROUNDS * 2, index -> {
            String orderNo = orderNos.get(index / 2);
            if (index % 2 == 0) {
                return (Callable<String>) () -> {
                    repository.payOrder(orderNo, ItFixtures.USER_ID, new PayOrderRequest("balance", "h5"));
                    return "PAY:" + orderNo;
                };
            }
            return (Callable<String>) () -> {
                repository.cancelOrder(orderNo, ItFixtures.USER_ID);
                return "CANCEL:" + orderNo;
            };
        });

        long paySucceeded = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded)
            .filter(o -> o.value().startsWith("PAY")).count();
        long cancelSucceeded = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded)
            .filter(o -> o.value().startsWith("CANCEL")).count();

        // 1) 每单只能被一种操作赢下：支付成功数 + 取消成功数 必须正好等于订单数
        assertThat(paySucceeded + cancelSucceeded)
            .as("""
                每笔订单上「支付」和「取消」互斥，成功总数必须等于订单数 %d。
                实际 支付成功=%d，取消成功=%d，合计=%d。
                合计大于订单数说明同一单被两种互斥操作同时接受。""",
                ROUNDS, paySucceeded, cancelSucceeded, paySucceeded + cancelSucceeded)
            .isEqualTo(ROUNDS);

        // 2) 终态必须落在合法集合里
        List<Map<String, Object>> orders = fixtures.query(
            "SELECT order_no, status, paid_at, delivery_status FROM orders ORDER BY id");
        assertThat(orders).hasSize(ROUNDS);
        for (Map<String, Object> order : orders) {
            assertThat(String.valueOf(order.get("status")))
                .as("订单 %s 终态非法", order.get("order_no"))
                .isIn(LEGAL_TERMINAL_STATES);
        }

        // 3) 不允许自相矛盾：CANCELLED 的订单不得有 paid_at，也不得占着卡
        List<Map<String, Object>> cancelledButPaid = fixtures.query("""
            SELECT order_no, status, paid_at FROM orders
            WHERE status = 'CANCELLED' AND paid_at IS NOT NULL
            """);
        assertThat(cancelledButPaid)
            .as("已取消的订单不能同时带着支付时间（既取消又扣款）：%s", cancelledButPaid)
            .isEmpty();

        List<Map<String, Object>> cancelledWithCard = fixtures.query("""
            SELECT o.order_no, o.status, c.id AS card_id FROM orders o
            JOIN cards c ON c.sold_order_id = o.id
            WHERE o.status = 'CANCELLED'
            """);
        assertThat(cancelledWithCard)
            .as("已取消的订单不能占着已售出的卡密（既取消又发货）：%s", cancelledWithCard)
            .isEmpty();

        List<Map<String, Object>> refundedAndDelivered = fixtures.query("""
            SELECT o.order_no FROM orders o
            JOIN refund_records r ON r.order_id = o.id
            WHERE o.status = 'DELIVERED'
            """);
        assertThat(refundedAndDelivered)
            .as("不允许「既退款又发货」的订单：%s", refundedAndDelivered)
            .isEmpty();

        // 4) 资金必须与支付成功数一致
        BigDecimal expectedBalance = balanceBefore.subtract(PRICE.multiply(BigDecimal.valueOf(paySucceeded)));
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("扣款金额必须与支付成功笔数（%d 笔 × %s）一致", paySucceeded, PRICE)
            .isEqualByComparingTo(expectedBalance);
    }
}
