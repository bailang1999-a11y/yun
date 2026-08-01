package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
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

    /**
     * 批次8C：支付流水<b>分页</b>快照。
     *
     * <p>原路径是 {@code selectSnapshots()} 全表捞出、再在控制层 {@code subList} 切页。
     * payment_records 每行带 {@code channel_payload}（渠道原始报文，通常是完整的支付网关响应 JSON），
     * 行本身就重；而这张表随每一次支付尝试增长，没有上界。
     * 管理后台打开「支付流水」第一页，等于把历史全部渠道报文读进堆。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与 {@code selectSnapshots} 完全一致：
     * id 兜底保证同秒写入的流水在翻页时顺序稳定，否则同一行可能在相邻两页重复出现、或整个漏掉。
     */
    @Select("""
        SELECT id, payment_no, order_id, order_no, user_id, channel, out_trade_no,
               amount, status, channel_payload, paid_at, created_at
        FROM payment_records
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<PaymentRecordEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectSnapshotPage} 同表、同 WHERE（此表无筛选条件），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM payment_records")
    long countSnapshots();

    @Delete("DELETE FROM payment_records WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
