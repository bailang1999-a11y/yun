package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.SmsLogRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    /**
     * 批次8C：短信日志<b>分页</b>快照。
     *
     * <p>sms_logs 每行带 {@code content}（已渲染的短信正文，内含卡密、验证码、
     * 订单号这类敏感明文）与 {@code mobile}。原来的全量读把全站历史短信正文
     * 一次性拉进堆：既是无上界的内存风险，也让一次普通的列表请求把全部用户手机号
     * 和卡密同时驻留在进程内。下推后单次只取一页。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与 {@code selectSnapshots} 一致。
     */
    @Select("""
        SELECT id, order_no, mobile, template_type, content, status, error_message, created_at
        FROM sms_logs
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<SmsLogRecordEntity> selectSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectSnapshotPage} 同表、同 WHERE（此表无筛选条件），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM sms_logs")
    long countSnapshots();
}
