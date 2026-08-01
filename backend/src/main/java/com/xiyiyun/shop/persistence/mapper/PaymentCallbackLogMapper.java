package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentCallbackLogMapper extends BaseMapper<PaymentCallbackLogEntity> {
    @Select("""
        SELECT id, provider, payment_no, order_no, callback_status, channel_trade_no,
               result, message, raw_payload, created_at
        FROM payment_callback_logs
        ORDER BY created_at DESC, id DESC
        """)
    List<PaymentCallbackLogEntity> selectSnapshots();

    /**
     * 批次8C：支付回调日志<b>分页</b>快照。
     *
     * <p>这张表是本批次里全量读代价最高的一张：每行都带 {@code raw_payload}
     * —— 支付渠道 POST 过来的完整原始报文，未做裁剪。回调量是订单量的数倍
     * （同一笔支付会被渠道重推、还有各种通知类型），且只写不删。
     * 全量读一次等于把历史所有渠道报文拼成一个巨型结果集搬进 JVM 堆，
     * 单次请求就可能打满堆，而调用方每次只展示一页。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与 {@code selectSnapshots} 一致，
     * id 兜底保证同秒到达的回调（重推场景很常见）翻页顺序稳定。
     */
    @Select("""
        SELECT id, provider, payment_no, order_no, callback_status, channel_trade_no,
               result, message, raw_payload, created_at
        FROM payment_callback_logs
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<PaymentCallbackLogEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectSnapshotPage} 同表、同 WHERE（此表无筛选条件），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM payment_callback_logs")
    long countSnapshots();

    @Delete("DELETE FROM payment_callback_logs WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
