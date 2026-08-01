package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PaymentCallbackRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发用例 3：同一支付回调重放 10 次。
 *
 * <p><b>预期红灯，暴露缺陷：支付回调没有落库级幂等。</b>
 * {@code InMemoryShopRepository.handlePaymentCallback} 靠 JVM 内的
 * {@code synchronized (orderLock)} + 内存里 payment.status=="SUCCESS" 判断做幂等，
 * 因此在<b>单进程</b>下订单状态确实只推进一次；但：
 * <ul>
 *   <li>每次重放都会 {@code recordPaymentCallback(...)} 往 payment_callback_logs 写一行，
 *       10 次重放留下 10 行；</li>
 *   <li>006 迁移已建好 {@code payment_callback_logs.idempotency_key} 与唯一索引
 *       {@code uk_payment_callback_idem}，但<b>生产代码从未写入该列</b>（全为 NULL，
 *       MySQL 唯一索引允许多个 NULL，等于索引形同虚设）；</li>
 *   <li>资金流水表 {@code user_balance_transactions} 一行都没有，
 *       「这笔钱只记账一次」无法从库里证明。</li>
 * </ul>
 * 也就是说幂等性完全依赖单进程内存锁，多实例部署或进程重启后即失效。
 */
class ConcurrentPaymentCallbackReplayIT extends AbstractIntegrationTest {

    private static final int REPLAYS = 10;
    private static final String ORDER_NO = "ITCALLBACK0001";
    private static final String PAYMENT_NO = "ITPAY0001";
    private static final BigDecimal PRICE = new BigDecimal("12.0000");

    @Test
    @DisplayName("同一回调重放 10 次：订单只支付一次、卡只发一次、回调日志只留一条")
    void replayedCallbackMustBeIdempotent() {
        fixtures.seedCardGoodsScenario(new BigDecimal("500.0000"), 1, PRICE);
        long cardId = fixtures.insertUnsoldCard("IT-CALLBACK-CARD-0001");
        long orderId = fixtures.insertOrder(ORDER_NO, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
            OrderStatus.UNPAID.name(), 1, PRICE);
        fixtures.insertPendingPayment(PAYMENT_NO, ORDER_NO, orderId, ItFixtures.USER_ID, PRICE);

        BigDecimal balanceBefore = fixtures.userBalance(ItFixtures.USER_ID);

        PaymentCallbackRequest request = new PaymentCallbackRequest(
            PAYMENT_NO, ORDER_NO, "SUCCESS", "CHANNEL-TRADE-0001", null);

        List<ConcurrentRunner.Outcome<OrderItem>> outcomes = ConcurrentRunner.runAll(REPLAYS,
            index -> () -> repository.handlePaymentCallback("mock", request));

        // 1) 支付记录只能有一条、且只成功一次
        List<Map<String, Object>> payments = fixtures.query(
            "SELECT payment_no, status, paid_at FROM payment_records WHERE order_no = ?", ORDER_NO);
        assertThat(payments).as("重放不应产生多条支付记录").hasSize(1);
        assertThat(String.valueOf(payments.get(0).get("status"))).isEqualTo("SUCCESS");

        // 2) 卡密只能被发一次
        assertThat(fixtures.countCardsByStatus("SOLD"))
            .as("重放 %d 次后被卖出的卡必须仍是 1 张", REPLAYS)
            .isEqualTo(1);
        List<Map<String, Object>> soldCard = fixtures.query(
            "SELECT status, sold_order_id FROM cards WHERE id = ?", cardId);
        assertThat(String.valueOf(soldCard.get(0).get("status"))).isEqualTo("SOLD");

        // 3) 余额只允许变动一次（回调路径不重复收款）
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("外部渠道回调不应重复扣用户余额")
            .isEqualByComparingTo(balanceBefore);

        // 4) 资金流水：这笔支付必须有且只有一条记账（006 已建表，代码尚未写入 → 预期红）
        int ledgerRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_balance_transactions WHERE biz_no = ?", Integer.class, ORDER_NO);
        assertThat(ledgerRows)
            .as("""
                同一笔支付在 user_balance_transactions 里必须恰好记账 1 次（uk_balance_tx_biz 保证幂等）。
                实际=%d 行。006 迁移已建好该表与唯一键，但生产代码从未写入资金流水。""", ledgerRows)
            .isEqualTo(1);

        // 5) 回调日志：幂等键应让 10 次重放只留 1 行
        int logRows = fixtures.countRows("payment_callback_logs");
        int distinctIdemKeys = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT idempotency_key) FROM payment_callback_logs", Integer.class);
        int nullIdemKeys = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_callback_logs WHERE idempotency_key IS NULL", Integer.class);
        assertThat(logRows)
            .as("""
                payment_callback_logs 应因幂等键 uk_payment_callback_idem 只保留 1 行。
                实际=%d 行，其中 idempotency_key 为 NULL 的有 %d 行，非空幂等键去重后仅 %d 个。
                成功回调线程数=%d。说明生产代码每次重放都无条件插一行日志，且从不填 idempotency_key，
                唯一索引因 MySQL 允许多个 NULL 而完全失效。""",
                logRows, nullIdemKeys, distinctIdemKeys, ConcurrentRunner.countSucceeded(outcomes))
            .isEqualTo(1);
    }
}
