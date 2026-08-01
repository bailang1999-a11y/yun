package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.PaymentCallbackLogItem;
import com.xiyiyun.shop.mvp.RefundItem;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import com.xiyiyun.shop.persistence.entity.RefundRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserBalanceTransactionEntity;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentRecordMapper;
import com.xiyiyun.shop.persistence.mapper.RefundRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserBalanceTransactionMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金与库存的原子操作落点（批次4）。
 *
 * <h2>为什么必须是独立的 Spring Bean</h2>
 * {@code @Transactional} 依赖 Spring 代理，<b>同类内部自调用不生效</b>。
 * {@code InMemoryShopRepository} 里的资金逻辑如果直接写在自己方法里加 {@code @Transactional}，
 * 由内部调用触发时事务根本不会开启。因此把「扣款 + 记账」「退款 + 记账」这类必须
 * 同生共死的组合放到本类，由 repository 作为<b>外部调用方</b>经代理进入，事务边界才真实存在。
 *
 * <h2>三条铁律</h2>
 * <ol>
 *   <li><b>不先查后改。</b>余额、库存、订单状态全部走条件 UPDATE，靠受影响行数判断成败。</li>
 *   <li><b>钱动必留账。</b>任何 users.balance 变动都在同一事务里写一行
 *       {@code user_balance_transactions}，唯一键 uk_balance_tx_biz 保证不重复记账。</li>
 *   <li><b>先锁人再记账。</b>每个资金方法入口先 {@code SELECT ... FOR UPDATE} 锁住用户行，
 *       把同一用户的资金操作串行化，使流水链 balance_before/balance_after 首尾相接、可审计。
 *       并发争抢的代价只落在同一用户上，不同用户互不阻塞。</li>
 * </ol>
 */
@Service
public class FundsLedgerStore {

    public static final String BIZ_ORDER_PAY = "ORDER_PAY";
    public static final String BIZ_ORDER_REFUND = "ORDER_REFUND";
    public static final String BIZ_RECHARGE = "RECHARGE";
    public static final String BIZ_ADMIN_ADJUST = "ADMIN_ADJUST";
    public static final String BIZ_PAYMENT_SETTLE = "PAYMENT_SETTLE";

    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private final UserBalanceTransactionMapper ledgerMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final OrderRecordMapper orderRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final OrderPersistenceMapper persistenceMapper = new OrderPersistenceMapper();

    public FundsLedgerStore(
        UserBalanceTransactionMapper ledgerMapper,
        RefundRecordMapper refundRecordMapper,
        OrderRecordMapper orderRecordMapper,
        PaymentRecordMapper paymentRecordMapper
    ) {
        this.ledgerMapper = ledgerMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.orderRecordMapper = orderRecordMapper;
        this.paymentRecordMapper = paymentRecordMapper;
    }

    /** 资金操作结果：变动前后余额 + 是否因幂等而跳过。 */
    public record FundsResult(BigDecimal balanceBefore, BigDecimal balanceAfter, boolean alreadyApplied) {
    }

    // ================================================================= 扣款

    /**
     * 订单支付扣款：条件 UPDATE 扣余额 + 写 DEBIT 流水，同一事务。
     *
     * @throws IllegalStateException 余额不足（条件 UPDATE 影响 0 行）
     */
    @Transactional
    public FundsResult debitForOrderPay(Long userId, BigDecimal amount, String orderNo, String remark) {
        return applyDebit(userId, amount, BIZ_ORDER_PAY, orderNo, remark, "余额不足，请先充值");
    }

    /** 管理员调减余额 / 保证金。 */
    @Transactional
    public FundsResult debitByAdmin(Long userId, BigDecimal amount, String bizNo, String remark, boolean deposit) {
        if (deposit) {
            return applyDepositChange(userId, amount, false, BIZ_ADMIN_ADJUST, bizNo, remark);
        }
        return applyDebit(userId, amount, BIZ_ADMIN_ADJUST, bizNo, remark, "余额不足，无法调减");
    }

    private FundsResult applyDebit(
        Long userId, BigDecimal amount, String bizType, String bizNo, String remark, String insufficientMessage
    ) {
        requirePositive(amount);
        lockUser(userId);
        if (ledgerMapper.countByBiz(bizType, bizNo) > 0) {
            // 同一笔业务已记过账：幂等返回，绝不二次扣款
            BigDecimal current = ledgerMapper.selectBalance(userId);
            return new FundsResult(current, current, true);
        }
        int affected = ledgerMapper.debitBalance(userId, amount);
        if (affected == 0) {
            throw new IllegalStateException(insufficientMessage);
        }
        BigDecimal after = ledgerMapper.selectBalance(userId);
        BigDecimal before = after.add(amount);
        writeLedger(userId, DEBIT, amount, before, after, bizType, bizNo, remark);
        return new FundsResult(before, after, false);
    }

    // ================================================================= 加款

    /** 充值到账。 */
    @Transactional
    public FundsResult creditForRecharge(Long userId, BigDecimal amount, String rechargeNo, String remark) {
        return applyCredit(userId, amount, BIZ_RECHARGE, rechargeNo, remark);
    }

    /** 管理员调增余额 / 保证金。 */
    @Transactional
    public FundsResult creditByAdmin(Long userId, BigDecimal amount, String bizNo, String remark, boolean deposit) {
        if (deposit) {
            return applyDepositChange(userId, amount, true, BIZ_ADMIN_ADJUST, bizNo, remark);
        }
        return applyCredit(userId, amount, BIZ_ADMIN_ADJUST, bizNo, remark);
    }

    private FundsResult applyCredit(Long userId, BigDecimal amount, String bizType, String bizNo, String remark) {
        requirePositive(amount);
        lockUser(userId);
        if (ledgerMapper.countByBiz(bizType, bizNo) > 0) {
            BigDecimal current = ledgerMapper.selectBalance(userId);
            return new FundsResult(current, current, true);
        }
        int affected = ledgerMapper.creditBalance(userId, amount);
        if (affected == 0) {
            throw new IllegalArgumentException("user not found");
        }
        BigDecimal after = ledgerMapper.selectBalance(userId);
        BigDecimal before = after.subtract(amount);
        writeLedger(userId, CREDIT, amount, before, after, bizType, bizNo, remark);
        return new FundsResult(before, after, false);
    }

    /**
     * 保证金变动。
     *
     * <p>保证金不走 user_balance_transactions（该表 balance_before/after 语义是余额），
     * 但仍用条件 UPDATE 保证原子性，避免与余额同样的丢失更新。
     */
    private FundsResult applyDepositChange(
        Long userId, BigDecimal amount, boolean increase, String bizType, String bizNo, String remark
    ) {
        requirePositive(amount);
        lockUser(userId);
        int affected = increase
            ? ledgerMapper.creditDeposit(userId, amount)
            : ledgerMapper.debitDeposit(userId, amount);
        if (affected == 0) {
            throw new IllegalStateException(increase ? "user not found" : "保证金不足，无法调减");
        }
        BigDecimal deposit = ledgerMapper.selectDeposit(userId);
        return new FundsResult(deposit, deposit, false);
    }

    // ================================================================= 退款（缺陷 A1）

    /**
     * 缺陷 A1 的修复落点：<b>写退款记录 + 给用户加回余额 + 写 CREDIT 流水，三件事同一事务</b>。
     *
     * <p>幂等由 uk_balance_tx_biz (ORDER_REFUND, orderNo) 保证：同一笔退款重复调用
     * 只会在第一次真正退钱，后续调用走 {@code alreadyApplied=true} 直接返回。
     *
     * <p>注意退款记录用 upsert（uk_refund_no / uk_refund_out_refund_no），
     * 因此重复调用也不会产生第二条退款单。
     */
    @Transactional
    public FundsResult refundToBalance(RefundItem refund, String reason) {
        requirePositive(refund.amount());
        Long userId = refund.userId();
        lockUser(userId);

        Long orderId = orderRecordMapper.findIdByOrderNo(refund.orderNo());
        if (orderId == null) {
            // refund_records.order_id 是 NOT NULL；订单快照缺失就不能假装退款成功
            throw new IllegalStateException("order snapshot not found: " + refund.orderNo());
        }
        Long paymentId = null;
        if (refund.paymentNo() != null) {
            PaymentRecordEntity payment = paymentRecordMapper.findLatestByOrderNo(refund.orderNo());
            if (payment != null && refund.paymentNo().equals(payment.getPaymentNo())) {
                paymentId = payment.getId();
            }
        }
        RefundRecordEntity entity = persistenceMapper.toRefundRecord(refund, orderId, paymentId);
        refundRecordMapper.upsertByRefundNo(entity);

        if (ledgerMapper.countByBiz(BIZ_ORDER_REFUND, refund.orderNo()) > 0) {
            BigDecimal current = ledgerMapper.selectBalance(userId);
            return new FundsResult(current, current, true);
        }
        int affected = ledgerMapper.creditBalance(userId, refund.amount());
        if (affected == 0) {
            throw new IllegalStateException("退款失败：用户不存在或已删除");
        }
        BigDecimal after = ledgerMapper.selectBalance(userId);
        BigDecimal before = after.subtract(refund.amount());
        writeLedger(userId, CREDIT, refund.amount(), before, after,
            BIZ_ORDER_REFUND, refund.orderNo(), reason);
        return new FundsResult(before, after, false);
    }

    /** 某笔订单是否已经退过钱（供人工补救判断，不看订单状态只看资金事实）。 */
    @Transactional(readOnly = true)
    public boolean refundAlreadySettled(String orderNo) {
        return ledgerMapper.countByBiz(BIZ_ORDER_REFUND, orderNo) > 0;
    }

    // ================================================================= 外部渠道支付结算

    /**
     * 外部渠道回调结算：记一对「入账 CREDIT + 订单扣款 DEBIT」，净额为 0。
     *
     * <p>为什么是两条而不是零条：钱确实从渠道流进来了，又立刻被这笔订单消耗掉。
     * 只记净额（不记账）会让「这笔支付到底记过一次账吗」无法从库里证明；
     * 只记 DEBIT 又会凭空扣走用户余额。两条流水是唯一诚实的记法，
     * 且各自带独立幂等键（PAYMENT_SETTLE/paymentNo、ORDER_PAY/orderNo），重放不会重复记。
     */
    @Transactional
    public void settleExternalPayment(Long userId, BigDecimal amount, String paymentNo, String orderNo) {
        requirePositive(amount);
        lockUser(userId);

        boolean settleRecorded = ledgerMapper.countByBiz(BIZ_PAYMENT_SETTLE, paymentNo) > 0;
        if (!settleRecorded) {
            if (ledgerMapper.creditBalance(userId, amount) == 0) {
                throw new IllegalStateException("支付结算失败：用户不存在");
            }
            BigDecimal after = ledgerMapper.selectBalance(userId);
            writeLedger(userId, CREDIT, amount, after.subtract(amount), after,
                BIZ_PAYMENT_SETTLE, paymentNo, "外部渠道支付到账");
        }
        boolean payRecorded = ledgerMapper.countByBiz(BIZ_ORDER_PAY, orderNo) > 0;
        if (!payRecorded) {
            if (ledgerMapper.debitBalance(userId, amount) == 0) {
                throw new IllegalStateException("支付结算失败：到账金额不足以支付订单");
            }
            BigDecimal after = ledgerMapper.selectBalance(userId);
            writeLedger(userId, DEBIT, amount, after.add(amount), after,
                BIZ_ORDER_PAY, orderNo, "外部渠道支付订单");
        }
    }

    // ================================================================= 库存（缺陷 A4）

    /**
     * 库存扣减：条件 UPDATE，受影响行数 0 即库存不足。
     *
     * <p>{@code REQUIRES_NEW}：库存扣减必须独立提交，不能被调用方后续的失败连带回滚，
     * 也不能反过来把调用方拖进自己的锁等待。归还由 {@link #restoreStock} 显式补偿。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deductStock(Long goodsId, int quantity) {
        if (goodsId == null || quantity <= 0) {
            return false;
        }
        return ledgerMapper.deductGoodsStock(goodsId, quantity) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void restoreStock(Long goodsId, int quantity) {
        if (goodsId != null && quantity > 0) {
            ledgerMapper.restoreGoodsStock(goodsId, quantity);
        }
    }

    /** 可售卡密数量：DB 是唯一事实来源（缺陷 A2/A3）。 */
    @Transactional(readOnly = true)
    public int availableCardCount(Long goodsId, Long cardKindId) {
        if (cardKindId != null) {
            return ledgerMapper.countAvailableCardsByCardKind(cardKindId);
        }
        if (goodsId == null) {
            return 0;
        }
        return ledgerMapper.countAvailableCardsByGoods(goodsId);
    }

    /** 把 CARD 商品标称库存对齐到真实可售卡密数。 */
    @Transactional
    public int syncCardGoodsStock(Long goodsId, Long cardKindId) {
        int available = availableCardCount(goodsId, cardKindId);
        ledgerMapper.syncGoodsStock(goodsId, available);
        return available;
    }

    // ================================================================= 订单状态 CAS

    /** 订单状态 CAS，true 表示本次抢到了状态变更权。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean compareAndSetStatus(String orderNo, String expected, String next) {
        return ledgerMapper.compareAndSetOrderStatus(orderNo, expected, next) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean compareAndSetStatusFromEither(String orderNo, String expectedA, String expectedB, String next) {
        return ledgerMapper.compareAndSetOrderStatusFromEither(orderNo, expectedA, expectedB, next) == 1;
    }

    @Transactional(readOnly = true)
    public String currentStatus(String orderNo) {
        return ledgerMapper.selectOrderStatus(orderNo);
    }

    // ================================================================= 回调幂等

    /**
     * 回调日志按幂等键去重落库，返回 true 表示这是首次记录。
     *
     * <p>幂等键只由「provider + 支付单号 + 订单号 + 回调状态 + 渠道流水号」计算，
     * 刻意<b>不含</b> result/message：同一个外部回调无论被我方判成 SUCCESS 还是
     * IDEMPOTENT，都必须collapse 成同一行，否则重放 10 次仍会留 10 行。
     */
    @Transactional
    public boolean recordCallbackOnce(PaymentCallbackLogItem log) {
        PaymentCallbackLogEntity entity = persistenceMapper.toPaymentCallbackLog(log);
        String key = callbackIdempotencyKey(log);
        return ledgerMapper.insertCallbackLogIfAbsent(entity, key) == 1;
    }

    static String callbackIdempotencyKey(PaymentCallbackLogItem log) {
        String raw = String.join("|",
            nullSafe(log.provider()),
            nullSafe(log.paymentNo()),
            nullSafe(log.orderNo()),
            nullSafe(log.status()),
            nullSafe(log.channelTradeNo()));
        return sha256Hex(raw);
    }

    // ================================================================= 内部

    private void lockUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("user not found");
        }
        BigDecimal locked = ledgerMapper.lockBalance(userId);
        if (locked == null) {
            throw new IllegalArgumentException("user not found");
        }
    }

    private void writeLedger(
        Long userId, String direction, BigDecimal amount,
        BigDecimal before, BigDecimal after, String bizType, String bizNo, String remark
    ) {
        UserBalanceTransactionEntity entity = new UserBalanceTransactionEntity();
        entity.setUserId(userId);
        entity.setDirection(direction);
        entity.setAmount(amount);
        entity.setBalanceBefore(before);
        entity.setBalanceAfter(after);
        entity.setBizType(bizType);
        entity.setBizNo(bizNo == null || bizNo.isBlank() ? bizType + "-" + userId : bizNo);
        entity.setRemark(remark);
        entity.setCreatedAt(OffsetDateTime.now());
        ledgerMapper.insertTransaction(entity);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
