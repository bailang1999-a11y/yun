package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.RefundRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecordEntity> {
    @Update("""
        INSERT INTO refund_records (
            refund_no, order_id, payment_id, user_id, out_refund_no, amount,
            reason, status, channel_payload, refunded_at, created_at
        ) VALUES (
            #{entity.refundNo}, #{entity.orderId}, #{entity.paymentId}, #{entity.userId}, #{entity.outRefundNo}, #{entity.amount},
            #{entity.reason}, #{entity.status}, #{entity.channelPayload}, #{entity.refundedAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            order_id = VALUES(order_id),
            payment_id = VALUES(payment_id),
            user_id = VALUES(user_id),
            amount = VALUES(amount),
            reason = VALUES(reason),
            status = VALUES(status),
            channel_payload = VALUES(channel_payload),
            refunded_at = VALUES(refunded_at)
        """)
    int upsertByRefundNo(@Param("entity") RefundRecordEntity entity);

    @Select("""
        SELECT r.id, r.refund_no, r.order_id, r.payment_id, o.order_no, p.payment_no,
               r.user_id, r.out_refund_no, r.amount, r.reason, r.status,
               r.channel_payload, r.refunded_at, r.created_at
        FROM refund_records r
        LEFT JOIN orders o ON o.id = r.order_id
        LEFT JOIN payment_records p ON p.id = r.payment_id
        ORDER BY r.created_at DESC, r.id DESC
        """)
    List<RefundRecordEntity> selectSnapshots();

    /**
     * 批次8C：退款流水<b>分页</b>快照。
     *
     * <p>原路径全表捞出后在控制层切页。退款行同样带 {@code channel_payload}（渠道退款报文），
     * 且这里是三表 JOIN：全量读会让 MySQL 为<b>每一条</b>历史退款各做两次主键回表
     * （orders、payment_records），代价随退款总量线性上升，而前端一次只要 10 行。
     *
     * <p>列清单、JOIN 与排序键 {@code (r.created_at DESC, r.id DESC)} 与
     * {@code selectSnapshots} 一字不改，只在末尾追加 LIMIT/OFFSET，保证下推前后行序一致。
     */
    @Select("""
        SELECT r.id, r.refund_no, r.order_id, r.payment_id, o.order_no, p.payment_no,
               r.user_id, r.out_refund_no, r.amount, r.reason, r.status,
               r.channel_payload, r.refunded_at, r.created_at
        FROM refund_records r
        LEFT JOIN orders o ON o.id = r.order_id
        LEFT JOIN payment_records p ON p.id = r.payment_id
        ORDER BY r.created_at DESC, r.id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<RefundRecordEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /**
     * 批次8C：供分页返回 total。
     *
     * <p><b>这里故意不带 JOIN。</b>{@link #selectSnapshotPage} 的两个 JOIN 都是 LEFT JOIN，
     * 且都按主键（{@code o.id} / {@code p.id}）等值关联：最多匹配一行，匹配不上也会补 NULL 保留左表行
     * —— 对 {@code refund_records} 的行数没有任何影响，JOIN 只是为了把 order_no / payment_no 带出来展示。
     * 既然行数与 JOIN 无关，count 就不该为此付两次回表的代价。
     * 筛选条件（此处为无）与取数保持一致，这才是 total 必须对齐的部分。
     */
    @Select("SELECT COUNT(*) FROM refund_records")
    long countSnapshots();

    @Delete("DELETE FROM refund_records WHERE order_id = #{orderId}")
    int hardDeleteByOrderId(@Param("orderId") Long orderId);
}
