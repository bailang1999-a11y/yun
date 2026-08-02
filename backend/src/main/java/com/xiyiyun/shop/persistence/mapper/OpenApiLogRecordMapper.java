package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OpenApiLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpenApiLogRecordMapper extends BaseMapper<OpenApiLogRecordEntity> {
    @Insert("""
        INSERT INTO open_api_logs (id, user_id, app_key, path, status, message, created_at)
        VALUES (#{id}, #{userId}, #{appKey}, #{path}, #{status}, #{message}, #{createdAt})
        """)
    int insertSnapshot(OpenApiLogRecordEntity entity);

    @Select("""
        SELECT id, user_id, app_key, path, status, message, created_at
        FROM open_api_logs
        ORDER BY created_at DESC, id DESC
        """)
    List<OpenApiLogRecordEntity> selectSnapshots();

    /**
     * 批次8C：开放接口调用日志<b>分页</b>快照。
     *
     * <p>open_api_logs 每次会员 API 调用写一行，是全库写入频率最高的表之一
     * （会员方轮询订单状态时一次下单能产生几十行）。原来展示列表要先把这张表
     * 整个读出来，量级比订单表还大一个数量级。
     *
     * <p>注意只有<b>展示</b>路径改走本方法。日限额统计
     * （{@code AuditService.allOpenApiLogSnapshots}）语义上需要全量并集，不在本次改动范围内。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与 {@code selectSnapshots} 一致。
     */
    @Select("""
        SELECT id, user_id, app_key, path, status, message, created_at
        FROM open_api_logs
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<OpenApiLogRecordEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectSnapshotPage} 同表、同 WHERE（此表无筛选条件），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM open_api_logs")
    long countSnapshots();

    @Select("SELECT COALESCE(MAX(id), 0) FROM open_api_logs")
    long selectMaxId();
}
