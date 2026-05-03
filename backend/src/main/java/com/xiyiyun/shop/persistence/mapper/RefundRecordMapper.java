package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.RefundRecordEntity;
import java.util.List;
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
}
