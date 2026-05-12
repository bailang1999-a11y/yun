package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OpenApiLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
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
}
