package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecordEntity> {
    @Update("""
        INSERT INTO payment_records (
            payment_no, order_id, order_no, user_id, channel, out_trade_no,
            amount, status, channel_payload, paid_at, created_at
        ) VALUES (
            #{entity.paymentNo}, #{entity.orderId}, #{entity.orderNo}, #{entity.userId}, #{entity.channel}, #{entity.outTradeNo},
            #{entity.amount}, #{entity.status}, #{entity.channelPayload}, #{entity.paidAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            order_id = VALUES(order_id),
            order_no = VALUES(order_no),
            user_id = VALUES(user_id),
            channel = VALUES(channel),
            out_trade_no = VALUES(out_trade_no),
            amount = VALUES(amount),
            status = VALUES(status),
            channel_payload = VALUES(channel_payload),
            paid_at = VALUES(paid_at)
        """)
    int upsertByPaymentNo(@Param("entity") PaymentRecordEntity entity);

    @Select("""
        SELECT id, payment_no, order_id, order_no, user_id, channel, out_trade_no,
               amount, status, channel_payload, paid_at, created_at
        FROM payment_records
        WHERE order_no = #{orderNo}
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """)
    PaymentRecordEntity findLatestByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
        SELECT id, payment_no, order_id, order_no, user_id, channel, out_trade_no,
               amount, status, channel_payload, paid_at, created_at
        FROM payment_records
        ORDER BY created_at DESC, id DESC
        """)
    List<PaymentRecordEntity> selectSnapshots();
}
