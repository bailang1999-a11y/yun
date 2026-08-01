package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.UserBalanceTransactionEntity;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 资金原子操作入口（批次4）。
 *
 * <p>所有余额 / 库存 / 订单状态变更都写成「条件更新」，由受影响行数判断成败，
 * 不做「先 SELECT 再算新值再写回」——后者在并发下必然丢失更新。
 */
@Mapper
public interface UserBalanceTransactionMapper extends BaseMapper<UserBalanceTransactionEntity> {

    // ------------------------------------------------------------------ 余额

    /**
     * 行锁：把同一用户的所有资金操作串行化。
     *
     * <p>作用不是"判断余额够不够"（那件事由下面的条件 UPDATE 负责），而是让
     * 「记账前余额 → 记账后余额」这条流水链在并发下保持连续可审计：
     * 拿到锁的线程独占该用户，直到事务提交才释放，因此流水行的自增 id 顺序
     * 与真实资金变动顺序一致，不会出现 balance_before 跳变。
     */
    @Select("SELECT balance FROM users WHERE id = #{userId} AND deleted_at IS NULL FOR UPDATE")
    BigDecimal lockBalance(@Param("userId") Long userId);

    /**
     * 扣款：余额充足才会更新，受影响行数 0 表示余额不足。
     * 这是「不靠先查后改」的核心——判断与写入是同一条 SQL 的原子动作。
     */
    @Update("""
        UPDATE users
        SET balance = balance - #{amount},
            version = version + 1
        WHERE id = #{userId}
          AND balance >= #{amount}
          AND deleted_at IS NULL
        """)
    int debitBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 加款：退款 / 充值 / 人工调增。 */
    @Update("""
        UPDATE users
        SET balance = balance + #{amount},
            version = version + 1
        WHERE id = #{userId}
          AND deleted_at IS NULL
        """)
    int creditBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 保证金扣减，语义与 debitBalance 一致。 */
    @Update("""
        UPDATE users
        SET deposit = deposit - #{amount},
            version = version + 1
        WHERE id = #{userId}
          AND deposit >= #{amount}
          AND deleted_at IS NULL
        """)
    int debitDeposit(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Update("""
        UPDATE users
        SET deposit = deposit + #{amount},
            version = version + 1
        WHERE id = #{userId}
          AND deleted_at IS NULL
        """)
    int creditDeposit(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Select("SELECT balance FROM users WHERE id = #{userId} AND deleted_at IS NULL")
    BigDecimal selectBalance(@Param("userId") Long userId);

    @Select("SELECT deposit FROM users WHERE id = #{userId} AND deleted_at IS NULL")
    BigDecimal selectDeposit(@Param("userId") Long userId);

    // ------------------------------------------------------------------ 流水

    /**
     * 写流水。故意用普通 INSERT 而不是 INSERT IGNORE：
     * 撞上 uk_balance_tx_biz 说明同一笔业务被重复记账，必须让事务回滚把资金变动一起撤销，
     * 而不是"悄悄吞掉"。调用方在同一事务里先查 {@link #countByBiz} 做常规幂等短路。
     */
    @Insert("""
        INSERT INTO user_balance_transactions (
            user_id, direction, amount, balance_before, balance_after,
            biz_type, biz_no, remark, created_at
        ) VALUES (
            #{entity.userId}, #{entity.direction}, #{entity.amount},
            #{entity.balanceBefore}, #{entity.balanceAfter},
            #{entity.bizType}, #{entity.bizNo}, #{entity.remark}, #{entity.createdAt}
        )
        """)
    int insertTransaction(@Param("entity") UserBalanceTransactionEntity entity);

    @Select("""
        SELECT COUNT(*) FROM user_balance_transactions
        WHERE biz_type = #{bizType} AND biz_no = #{bizNo}
        """)
    int countByBiz(@Param("bizType") String bizType, @Param("bizNo") String bizNo);

    // ------------------------------------------------------------------ 库存

    /** 库存扣减：库存充足才更新，受影响行数 0 表示不足（超卖被数据库挡住）。 */
    @Update("""
        UPDATE goods
        SET stock_count = stock_count - #{quantity},
            version = version + 1
        WHERE id = #{goodsId}
          AND stock_count >= #{quantity}
          AND deleted_at IS NULL
        """)
    int deductGoodsStock(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);

    /** 取消 / 退款时归还库存。 */
    @Update("""
        UPDATE goods
        SET stock_count = stock_count + #{quantity},
            version = version + 1
        WHERE id = #{goodsId}
          AND deleted_at IS NULL
        """)
    int restoreGoodsStock(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);

    /** 把 CARD 商品的标称库存对齐到真实可售卡密数（DB 是唯一事实来源）。 */
    @Update("""
        UPDATE goods
        SET stock_count = #{stockCount},
            version = version + 1
        WHERE id = #{goodsId}
          AND deleted_at IS NULL
        """)
    int syncGoodsStock(@Param("goodsId") Long goodsId, @Param("stockCount") int stockCount);

    @Select("""
        SELECT COUNT(*) FROM cards
        WHERE goods_id = #{goodsId} AND status = 'UNSOLD' AND deleted_at IS NULL
        """)
    int countAvailableCardsByGoods(@Param("goodsId") Long goodsId);

    @Select("""
        SELECT COUNT(*) FROM cards
        WHERE card_kind_id = #{cardKindId} AND status = 'UNSOLD' AND deleted_at IS NULL
        """)
    int countAvailableCardsByCardKind(@Param("cardKindId") Long cardKindId);

    // ------------------------------------------------------------------ 订单状态机 CAS

    /**
     * 订单状态条件更新（CAS）。受影响行数 0 表示订单已被别的操作推进过，
     * 当前这次状态变更必须判定失败——这是「支付」与「取消」互斥的落库级保证。
     */
    @Update("""
        UPDATE orders
        SET status = #{nextStatus},
            version = version + 1
        WHERE order_no = #{orderNo}
          AND status = #{expectedStatus}
          AND deleted_at IS NULL
        """)
    int compareAndSetOrderStatus(
        @Param("orderNo") String orderNo,
        @Param("expectedStatus") String expectedStatus,
        @Param("nextStatus") String nextStatus
    );

    /** 允许多个前置状态的 CAS（如 CREATED/UNPAID 都可取消）。 */
    @Update("""
        UPDATE orders
        SET status = #{nextStatus},
            version = version + 1
        WHERE order_no = #{orderNo}
          AND status IN (#{expectedA}, #{expectedB})
          AND deleted_at IS NULL
        """)
    int compareAndSetOrderStatusFromEither(
        @Param("orderNo") String orderNo,
        @Param("expectedA") String expectedA,
        @Param("expectedB") String expectedB,
        @Param("nextStatus") String nextStatus
    );

    @Select("SELECT status FROM orders WHERE order_no = #{orderNo} AND deleted_at IS NULL")
    String selectOrderStatus(@Param("orderNo") String orderNo);

    // ------------------------------------------------------------------ 回调幂等

    /**
     * 回调日志按幂等键去重落库。INSERT IGNORE 依赖 006 建好的
     * uk_payment_callback_idem (idempotency_key)：重放只会留下第一行。
     */
    @Insert("""
        INSERT IGNORE INTO payment_callback_logs (
            provider, payment_no, order_no, callback_status, channel_trade_no,
            result, message, raw_payload, idempotency_key, created_at
        ) VALUES (
            #{entity.provider}, #{entity.paymentNo}, #{entity.orderNo}, #{entity.callbackStatus},
            #{entity.channelTradeNo}, #{entity.result}, #{entity.message}, #{entity.rawPayload},
            #{idempotencyKey}, #{entity.createdAt}
        )
        """)
    int insertCallbackLogIfAbsent(
        @Param("entity") com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity entity,
        @Param("idempotencyKey") String idempotencyKey
    );
}
