package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OperationLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OperationLogRecordMapper extends BaseMapper<OperationLogRecordEntity> {
    @Insert("""
        INSERT INTO admin_operation_logs (id, admin_name, action, resource_type, resource_id, after_data, created_at)
        VALUES (#{id}, #{adminName}, #{action}, #{resourceType}, #{resourceId}, JSON_OBJECT('remark', #{afterData}), #{createdAt})
        """)
    int insertSnapshot(OperationLogRecordEntity entity);

    @Select("""
        SELECT id, admin_name, action, resource_type, resource_id, after_data, created_at
        FROM admin_operation_logs
        ORDER BY created_at DESC, id DESC
        """)
    List<OperationLogRecordEntity> selectSnapshots();

    /**
     * 批次8C：管理操作审计日志<b>分页</b>快照。
     *
     * <p>这张表增长速度和管理端的每一次写操作绑定（改价、改库存、发卡、退款……），
     * 而且 {@code after_data} 是 JSON 列。审计表按设计只追加不清理，所以它是
     * 库里最先突破百万行的表之一；原来的全量读会随运营时间线性变慢，
     * 最终「操作日志」页面变成打开就打满堆的接口。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与 {@code selectSnapshots} 一致。
     */
    @Select("""
        SELECT id, admin_name, action, resource_type, resource_id, after_data, created_at
        FROM admin_operation_logs
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<OperationLogRecordEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectSnapshotPage} 同表、同 WHERE（此表无筛选条件），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM admin_operation_logs")
    long countSnapshots();

    @Select("SELECT COALESCE(MAX(id), 0) FROM admin_operation_logs")
    long selectMaxId();
}
