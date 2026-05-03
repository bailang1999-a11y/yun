package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OperationLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
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
}
