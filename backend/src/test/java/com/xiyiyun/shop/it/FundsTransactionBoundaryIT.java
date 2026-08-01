package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.RefundItem;
import com.xiyiyun.shop.persistence.FundsLedgerStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 批次4 的事务边界取证。
 *
 * <p>{@code @Transactional} 靠 Spring 代理生效，同类内部自调用会被绕过。资金逻辑因此被放进
 * 独立 bean {@link FundsLedgerStore}。本用例不"假设"代理生效，而是<b>实测</b>两件事：
 * <ol>
 *   <li>该 bean 确实被 AOP 代理包裹；</li>
 *   <li>「加余额 + 写流水」是原子的——让第二步（写流水）在库层失败，
 *       第一步（余额已 +18.5）必须被回滚掉。若事务没生效，余额会留下无账可查的增加。</li>
 * </ol>
 */
class FundsTransactionBoundaryIT extends AbstractIntegrationTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("500.0000");
    private static final BigDecimal PRICE = new BigDecimal("18.5000");

    @Autowired
    private FundsLedgerStore fundsLedgerStore;

    @Test
    @DisplayName("资金 store 必须是 Spring 代理对象（否则 @Transactional 形同注释）")
    void fundsLedgerStoreMustBeProxied() {
        assertThat(AopUtils.isAopProxy(fundsLedgerStore))
            .as("FundsLedgerStore 必须被 Spring AOP 代理，否则 @Transactional 不会开启事务")
            .isTrue();
    }

    @Test
    @DisplayName("退款事务：写流水失败时，已加的余额必须回滚")
    void refundMustRollbackBalanceWhenLedgerInsertFails() {
        fixtures.seedCardGoodsScenario(INITIAL_BALANCE, 1, PRICE);
        String orderNo = "ITTXN0001";
        fixtures.insertOrder(orderNo, ItFixtures.USER_ID, ItFixtures.GOODS_ID, "CARD",
            "UNPAID", 1, PRICE);

        BigDecimal before = fixtures.userBalance(ItFixtures.USER_ID);

        // remark 列是 VARCHAR(500)，超长会在 STRICT 模式下报错 —— 用它让流水 INSERT 失败，
        // 而此时"余额 +18.5"这条 UPDATE 已经在同一事务里执行过了。
        String oversizedRemark = "x".repeat(600);
        RefundItem refund = new RefundItem(
            "ITTXNREFUND0001", orderNo, null, ItFixtures.USER_ID, PRICE,
            "SUCCESS", oversizedRemark, OffsetDateTime.now(), OffsetDateTime.now());

        assertThatThrownBy(() -> fundsLedgerStore.refundToBalance(refund, oversizedRemark))
            .as("流水写入失败必须抛出，不能悄悄吞掉")
            .isInstanceOf(RuntimeException.class);

        assertThat(fixtures.userBalance(ItFixtures.USER_ID))
            .as("流水没写成功，余额的 +%s 必须一起回滚；余额变了但无账可查是不可接受的", PRICE)
            .isEqualByComparingTo(before);
        assertThat(fixtures.countRows("user_balance_transactions"))
            .as("回滚后不应残留流水")
            .isEqualTo(0);
        assertThat(fixtures.countRows("refund_records"))
            .as("同一事务里的退款记录也必须回滚")
            .isEqualTo(0);
    }
}
