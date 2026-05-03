package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.SmsLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SmsLogRecordMapper extends BaseMapper<SmsLogRecordEntity> {
    @Insert("""
        INSERT INTO sms_logs (id, order_no, mobile, template_type, content, status, error_message, created_at)
        VALUES (#{id}, #{orderNo}, #{mobile}, #{templateType}, #{content}, #{status}, #{errorMessage}, #{createdAt})
        """)
    int insertSnapshot(SmsLogRecordEntity entity);

    @Select("""
        SELECT id, order_no, mobile, template_type, content, status, error_message, created_at
        FROM sms_logs
        ORDER BY created_at DESC, id DESC
        """)
    List<SmsLogRecordEntity> selectSnapshots();
}
