package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CreateOrderRequest;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.PayOrderRequest;
import com.xiyiyun.shop.mvp.UpdateSystemSettingRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务D：下单 → 支付 → 发货 → 退款 全链路，逐步断言四件事一致：
 * 用户余额、商品库存、卡密状态、资金流水。
 *
 * <p><b>预期红灯</b>，分别暴露三个独立缺陷：
 * <ol>
 *   <li>{@code createOrder → refreshStock}（9547-9551 行）对 CARD 商品用内存 {@code cards} map
 *       统计库存，而持久化模式下该 map 恒为空 → 库里明明有可售卡密，下单仍抛
 *       {@code goods stock is insufficient}，卡密商品链路整条走不通。</li>
 *   <li>支付 / 发货全程不写 {@code user_balance_transactions}，006 迁移建好的资金流水表零写入点，
 *       余额变动无账可查。</li>
 *   <li>{@code createRefund}（7772-7795 行）只写退款记录，<b>不给用户加回余额</b>，
 *       订单标记 REFUNDED 而钱留在平台账上。</li>
 * </ol>
 *
 * <p>断言按要求严格写死「退款后余额 == 支付前余额」，不放宽成软条件。
 * 后三个用例为绕开缺陷 1 的遮挡，直接预置订单行再驱动 {@code payOrder}，
 * 这样支付、发货、退款三段仍是生产代码原样执行。
 */
class OrderFullChainIT extends AbstractIntegrationTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("500.0000");
    private static final BigDecimal PRICE = new BigDecimal("18.5000");

    @Test
    @DisplayName("下单：库中有可售卡密时必须允许下单（预期红灯，暴露 refreshStock 只看内存）")
    void createOrderMustSeePersistentCardStock() {
        fixtures.seedCardGoodsScenario(INITIAL_BALANCE, 1, PRICE);
        fixtures.insertUnsoldCard("IT-CHAIN-CARD-0001");

        assertThat(fixtures.goodsStock(ItFixtures.GOODS_ID))
            .as("前置条件：goods.stock_count 与库中 UNSOLD 卡密均为 1")
            .isEqualTo(1);
        assertThat(fixtures.countCardsByStatus("UNSOLD")).isEqualTo(1);

        OrderItem created = repository.createOrder(new CreateOrderRequest(
            ItFixtures.GOODS_ID, 1, null, "全链路用例", "IT-CHAIN-1", "h5", Map.of()),
            ItFixtures.USER_ID, "127.0.0.1", "h5");

        assertThat(created.status()).isEqualTo(OrderStatus.UNPAID);
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("仅下单未支付，余额不应变动")
            .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(fixtures.countCardsByStatus("UNSOLD"))
            .as("未支付前卡密仍应可售")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("支付→发货：余额/库存/卡密/资金流水四者必须同步（预期红灯，暴露资金流水零写入）")
    void payAndDeliverMustKeepBalanceStockCardAndLedgerConsistent() {
        fixtures.seedCardGoodsScenario(INITIAL_BALANCE, 1, PRICE);
        long cardId = fixtures.insertUnsoldCard("IT-CHAIN-CARD-0002");
        // 绕开缺陷 1（refreshStock 只看内存 map）：直接预置一条 UNPAID 订单行。
        String orderNo = "ITCHAIN0002";
        fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
            "UNPAID", 1, PRICE);

        OrderItem paid = repository.payOrder(orderNo, ItFixtures.USER_ID,
            new PayOrderRequest("balance", "h5"));

        // ---- 余额 ----
        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("支付后余额必须恰好扣掉订单金额 %s", PRICE)
            .isEqualByComparingTo(INITIAL_BALANCE.subtract(PRICE));

        // ---- 卡密 ----
        assertThat(paid.status()).as("卡密商品支付成功后应直接发货完成").isEqualTo(OrderStatus.DELIVERED);
        List<Map<String, Object>> card = fixtures.query(
            "SELECT status, sold_order_id FROM cards WHERE id = ?", cardId);
        assertThat(String.valueOf(card.get(0).get("status")))
            .as("发货后卡密状态必须是 SOLD").isEqualTo("SOLD");
        assertThat(card.get(0).get("sold_order_id"))
            .as("发货后卡密必须绑定订单").isNotNull();

        // ---- 库存 ----
        assertThat(fixtures.goodsStock(ItFixtures.GOODS_ID))
            .as("发货后商品库存应从 1 变成 0，实际=%s", fixtures.goodsStock(ItFixtures.GOODS_ID))
            .isEqualTo(0);

        // ---- 资金流水 ----
        int ledgerRows = fixtures.countRows("user_balance_transactions");
        assertThat(ledgerRows)
            .as("""
                一次余额支付必须留下 1 条资金流水（direction=DEBIT, biz_type=ORDER_PAY），实际 %d 条。
                006 迁移已建好 user_balance_transactions 与唯一索引 uk_balance_tx_biz，
                但生产代码（payOrder / adjustUserFunds / createRefund）没有任何一处写入 ——
                余额变了却查不到账。""", ledgerRows)
            .isEqualTo(1);
    }

    @Test
    @DisplayName("支付→退款：退款后余额必须回到支付前，且扣款/退款各留一条流水")
    void refundMustRestoreBalanceStockAndCard() {
        // 开启「卡密库存不足自动退款」，让链路走到 createRefund
        repository.updateSystemSetting(new UpdateSystemSettingRequest(
            null, null, null, null, null, null, null, null,
            Boolean.TRUE, null, null, null, null, null, null, null, null, null));

        // 商品标称库存 1，但库里没有任何可用卡 → 支付成功后发货失败 → 自动退款
        fixtures.seedCardGoodsScenario(INITIAL_BALANCE, 1, PRICE);
        String orderNo = "ITCHAIN0003";
        fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
            "UNPAID", 1, PRICE);

        BigDecimal balanceBeforePay = fixtures.userBalance(ItFixtures.USER_ID);
        assertThat(balanceBeforePay).isEqualByComparingTo(INITIAL_BALANCE);

        OrderItem afterPay = repository.payOrder(orderNo, ItFixtures.USER_ID,
            new PayOrderRequest("balance", "h5"));

        // 支付阶段确实扣了钱。
        // 注意：自动退款是同步跑在 payOrder 内部的（下面紧接着断言终态已是 REFUNDED），
        // 所以 payOrder 返回后当前余额已经被退回，不能用「当前余额 == 支付前 - 单价」来验扣款。
        // 扣款事实必须查资金流水里那条 DEBIT/ORDER_PAY —— 这也正是流水存在的意义：
        // 余额是最终态，流水才是过程证据。
        List<Map<String, Object>> debitRows = fixtures.query(
            """
            SELECT amount, balance_before, balance_after
            FROM user_balance_transactions
            WHERE user_id = ? AND direction = 'DEBIT' AND biz_type = 'ORDER_PAY'
            """, ItFixtures.USER_ID);
        assertThat(debitRows).as("支付必须留下恰好 1 条 DEBIT/ORDER_PAY 流水").hasSize(1);
        assertThat((BigDecimal) debitRows.get(0).get("amount"))
            .as("扣款流水金额必须等于订单金额 %s", PRICE)
            .isEqualByComparingTo(PRICE);
        assertThat((BigDecimal) debitRows.get(0).get("balance_before"))
            .as("扣款前余额应为 %s", balanceBeforePay)
            .isEqualByComparingTo(balanceBeforePay);
        assertThat((BigDecimal) debitRows.get(0).get("balance_after"))
            .as("扣款后余额应为 %s", balanceBeforePay.subtract(PRICE))
            .isEqualByComparingTo(balanceBeforePay.subtract(PRICE));

        // 自动退款流程已跑过：订单 REFUNDED，退款记录落库
        assertThat(afterPay.status())
            .as("卡密不足 + 自动退款开启，订单终态应为 REFUNDED（实际=%s）", afterPay.status())
            .isEqualTo(OrderStatus.REFUNDED);
        List<Map<String, Object>> refunds = fixtures.query(
            """
            SELECT r.refund_no, r.amount, r.status
            FROM refund_records r JOIN orders o ON o.id = r.order_id
            WHERE o.order_no = ?
            """, orderNo);
        assertThat(refunds).as("必须有退款记录").hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount"))
            .as("退款金额必须等于实付金额")
            .isEqualByComparingTo(PRICE);

        // ---- 核心资金断言：退款后余额必须回到支付前 ----
        BigDecimal balanceAfterRefund = fixtures.userBalance(ItFixtures.USER_ID);
        assertThat(balanceAfterRefund)
            .as("""
                退款成功后用户余额必须回到支付前的 %s，实际=%s，少退 %s。
                这是缺陷 A1 的守门断言：原 createRefund 只做
                refunds.put + persistRefundSnapshot + appendOperation，
                从不给用户加回余额 —— 订单标记 REFUNDED 而钱被平台白拿。
                批次4 已改为在同一事务内写退款记录 + 加回余额 + 记 CREDIT 流水，
                此断言若再变红，说明退款回款链路被改坏了。""",
                balanceBeforePay, balanceAfterRefund, balanceBeforePay.subtract(balanceAfterRefund))
            .isEqualByComparingTo(balanceBeforePay);

        // ---- 退款后卡密不应被占用 ----
        assertThat(fixtures.countCardsByStatus("SOLD"))
            .as("退款订单不应占用任何卡密")
            .isEqualTo(0);

        // ---- 资金流水：支付 1 条 DEBIT + 退款 1 条 CREDIT ----
        int ledgerRows = fixtures.countRows("user_balance_transactions");
        assertThat(ledgerRows)
            .as("支付 + 退款应留下 2 条资金流水（DEBIT/ORDER_PAY 与 CREDIT/ORDER_REFUND），实际 %d 条",
                ledgerRows)
            .isEqualTo(2);
    }

    @Test
    @DisplayName("退款记录本身必须可信：金额为正、状态终态、订单不可重复支付")
    void refundRecordMustBeWellFormed() {
        repository.updateSystemSetting(new UpdateSystemSettingRequest(
            null, null, null, null, null, null, null, null,
            Boolean.TRUE, null, null, null, null, null, null, null, null, null));
        fixtures.seedCardGoodsScenario(INITIAL_BALANCE, 1, PRICE);
        String orderNo = "ITCHAIN0004";
        fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
            "UNPAID", 1, PRICE);
        repository.payOrder(orderNo, ItFixtures.USER_ID, new PayOrderRequest("balance", "h5"));

        List<Map<String, Object>> refunds = fixtures.query(
            """
            SELECT r.amount, r.status, r.reason
            FROM refund_records r JOIN orders o ON o.id = r.order_id
            WHERE o.order_no = ?
            """, orderNo);
        assertThat(refunds).hasSize(1);
        assertThat((BigDecimal) refunds.get(0).get("amount")).isGreaterThan(BigDecimal.ZERO);
        assertThat(String.valueOf(refunds.get(0).get("status")))
            .as("自动退款完成后状态不应停留在中间态")
            .isIn("SUCCESS", "REFUNDED", "DONE");

        // 已退款订单不允许再次支付
        assertThatThrownBy(() -> repository.payOrder(orderNo, ItFixtures.USER_ID,
            new PayOrderRequest("balance", "h5")))
            .as("已退款订单必须拒绝重复支付")
            .isInstanceOf(IllegalStateException.class);
    }
}
