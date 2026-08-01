package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.it.support.ConcurrentRunner;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.CreateOrderRequest;
import com.xiyiyun.shop.mvp.PayOrderRequest;
import com.xiyiyun.shop.mvp.UserFundAdjustRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 并发用例 4：同一用户并发扣款 + 并发充值。
 *
 * <p><b>预期红灯，暴露缺陷：余额是内存读-改-写，且锁对象不一致；资金流水表根本没人写。</b>
 * <ul>
 *   <li>扣款走 {@code payOrder}，在 {@code synchronized (orderLock)} 里做
 *       「读 user.balance() → 减 → users.put() → persistUserSnapshot()」；</li>
 *   <li>加款走 {@code adjustUserFunds}，方法签名是 {@code public synchronized}，锁的是
 *       {@code this}（repository 实例）。</li>
 * </ul>
 * 两者<b>持的是不同的监视器</b>，互不排斥，因此「读余额」和「写余额」之间存在真实的交叉窗口，
 * 会产生丢失更新（lost update）。数据库层面既没有 {@code SELECT ... FOR UPDATE}，
 * 也没有 {@code UPDATE users SET balance = balance - ?} 这种原子表达式，
 * 006 建好的 {@code user_balance_transactions} 更是一行都不会写。
 *
 * <p>金额设计：初始余额足够大，任何线程都不会走「余额不足」分支，
 * 于是期望值是确定的：{@code 期望 = 初始 - M*单价 + M*充值额}。
 */
class ConcurrentBalanceLedgerIT extends AbstractIntegrationTest {

    private static final int PAIRS = 15;                                  // 扣款线程数 = 充值线程数
    private static final BigDecimal INITIAL = new BigDecimal("100000.0000");
    private static final BigDecimal PRICE = new BigDecimal("7.0000");     // 每次扣款
    private static final BigDecimal CREDIT = new BigDecimal("3.0000");    // 每次充值

    @Test
    @DisplayName("并发扣款+充值：最终余额必须等于数学期望，且流水连续可审计")
    void balanceMustMatchArithmeticExpectation() {
        fixtures.seedCardGoodsScenario(INITIAL, PAIRS, PRICE);
        for (int i = 0; i < PAIRS; i++) {
            fixtures.insertUnsoldCard("IT-BALANCE-CARD-" + String.format("%04d", i));
        }

        // 预先建好 PAIRS 个 UNPAID 订单，让并发阶段只做「支付扣款」这一件事。
        // 直接落库而非走 createOrder：CARD 类商品的 createOrder 会被 refreshStock 拦住
        // （缺陷 A2，库里有卡但内存 cards Map 为空导致可售数算成 0），那是 OrderFullChainIT
        // 负责暴露的缺陷。本用例要测的是资金并发，必须绕开该遮挡才能触达自己的断言。
        List<String> orderNos = new ArrayList<>();
        for (int i = 0; i < PAIRS; i++) {
            String orderNo = String.format("ITBAL%04d", i);
            fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
                "UNPAID", 1, PRICE);
            orderNos.add(orderNo);
        }

        BigDecimal expected = INITIAL
            .subtract(PRICE.multiply(BigDecimal.valueOf(PAIRS)))
            .add(CREDIT.multiply(BigDecimal.valueOf(PAIRS)));

        List<ConcurrentRunner.Outcome<String>> outcomes = ConcurrentRunner.runAll(PAIRS * 2, index -> {
            if (index < PAIRS) {
                String orderNo = orderNos.get(index);
                return (Callable<String>) () -> {
                    repository.payOrder(orderNo, ItFixtures.USER_ID, new PayOrderRequest("balance", "h5"));
                    return "DEBIT:" + orderNo;
                };
            }
            return (Callable<String>) () -> {
                repository.adjustUserFunds(ItFixtures.USER_ID,
                    new UserFundAdjustRequest("balance", "increase", CREDIT, "并发充值"));
                return "CREDIT";
            };
        });

        long debitSucceeded = outcomes.stream()
            .filter(ConcurrentRunner.Outcome::succeeded)
            .filter(outcome -> outcome.value().startsWith("DEBIT"))
            .count();
        long creditSucceeded = outcomes.stream()
            .filter(ConcurrentRunner.Outcome::succeeded)
            .filter(outcome -> "CREDIT".equals(outcome.value()))
            .count();

        assertThat(debitSucceeded).as("每笔订单都有足额余额，扣款线程必须全部成功；失败原因=%s",
            ConcurrentRunner.failureMessages(outcomes).stream().distinct().limit(3).toList())
            .isEqualTo(PAIRS);
        assertThat(creditSucceeded).as("充值线程必须全部成功").isEqualTo(PAIRS);

        BigDecimal actual = fixtures.userBalance(ItFixtures.USER_ID);
        assertThat(actual)
            .as("""
                最终余额必须等于数学期望。
                初始=%s，扣款 %d 次 × %s，充值 %d 次 × %s，期望=%s，实际=%s，差额=%s。
                差额非零即为丢失更新：payOrder 在 orderLock 上扣款，adjustUserFunds 在 this 上加款，
                两把锁互不排斥，"读余额→算新值→写回"之间存在交叉窗口。""",
                INITIAL, PAIRS, PRICE, PAIRS, CREDIT, expected, actual, actual.subtract(expected))
            .isEqualByComparingTo(expected);

        // 资金流水：每次余额变动都必须留一条账，共 2*PAIRS 条
        int ledgerRows = fixtures.countRows("user_balance_transactions");
        assertThat(ledgerRows)
            .as("""
                每次余额变动都应写 user_balance_transactions：期望 %d 条（%d 笔扣款 + %d 笔充值），实际 %d 条。
                006 迁移已建好该表，但生产代码中没有任何写入点，资金变动完全不可审计。""",
                PAIRS * 2, PAIRS, PAIRS, ledgerRows)
            .isEqualTo(PAIRS * 2);

        // 流水链必须连续：按时间排序后，每行 balance_before 必须等于上一行 balance_after
        List<Map<String, Object>> ledger = fixtures.query("""
            SELECT id, direction, amount, balance_before, balance_after, biz_type, biz_no
            FROM user_balance_transactions WHERE user_id = ? ORDER BY id
            """, ItFixtures.USER_ID);
        BigDecimal running = INITIAL;
        for (Map<String, Object> row : ledger) {
            BigDecimal before = (BigDecimal) row.get("balance_before");
            BigDecimal after = (BigDecimal) row.get("balance_after");
            BigDecimal amount = (BigDecimal) row.get("amount");
            String direction = String.valueOf(row.get("direction"));
            assertThat(before)
                .as("流水 id=%s 的 balance_before 必须衔接上一条的 balance_after（不能跳变）", row.get("id"))
                .isEqualByComparingTo(running);
            BigDecimal computed = "DEBIT".equals(direction) ? before.subtract(amount) : before.add(amount);
            assertThat(after)
                .as("流水 id=%s 的 balance_after 必须等于 before %s amount", row.get("id"),
                    "DEBIT".equals(direction) ? "-" : "+")
                .isEqualByComparingTo(computed);
            running = after;
        }
        assertThat(running)
            .as("流水链末尾余额必须与 users.balance 一致")
            .isEqualByComparingTo(actual);

        // 订单侧也不能出问题：PAIRS 单全部支付成功
        int paidOrders = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE status IN (?, ?)", Integer.class,
            OrderStatus.DELIVERED.name(), OrderStatus.PAID.name());
        assertThat(paidOrders).as("%d 单应全部支付并发货成功", PAIRS).isEqualTo(PAIRS);
    }
}
