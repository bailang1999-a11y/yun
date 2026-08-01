package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.ProductMonitorStateRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：商品监控调度状态（原 system_settings 的 product.monitor.state.{channelId}）。
 *
 * <p>批次6 因为 {@code SystemSettingRecordMapper} 没有 DELETE，只能写空串当墓碑。
 * 本表有真正的 {@link #deleteByChannelId(Long)}，渠道删除后状态行随之消失。
 */
@Mapper
public interface ProductMonitorStateRecordMapper extends BaseMapper<ProductMonitorStateRecordEntity> {
    @Update("""
        INSERT INTO product_monitor_states (
            channel_id, last_scan_at, next_scan_at, last_result, last_message, scan_count, change_count
        ) VALUES (
            #{entity.channelId}, #{entity.lastScanAt}, #{entity.nextScanAt}, #{entity.lastResult},
            #{entity.lastMessage}, #{entity.scanCount}, #{entity.changeCount}
        )
        ON DUPLICATE KEY UPDATE
            last_scan_at = VALUES(last_scan_at),
            next_scan_at = VALUES(next_scan_at),
            last_result = VALUES(last_result),
            last_message = VALUES(last_message),
            scan_count = VALUES(scan_count),
            change_count = VALUES(change_count)
        """)
    int upsertSnapshot(@Param("entity") ProductMonitorStateRecordEntity entity);

    @Select("""
        SELECT channel_id, last_scan_at, next_scan_at, last_result, last_message, scan_count, change_count
        FROM product_monitor_states
        ORDER BY channel_id
        """)
    List<ProductMonitorStateRecordEntity> selectAllSnapshots();

    @Delete("DELETE FROM product_monitor_states WHERE channel_id = #{channelId}")
    int deleteByChannelId(@Param("channelId") Long channelId);
}
