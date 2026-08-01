package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.UserCredentialRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：会员登录口令哈希（原 system_settings 的 user.password.{userId}）。
 */
@Mapper
public interface UserCredentialRecordMapper extends BaseMapper<UserCredentialRecordEntity> {
    @Update("""
        INSERT INTO user_credentials (user_id, password_hash)
        VALUES (#{userId}, #{passwordHash})
        ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash)
        """)
    int upsertPasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Select("""
        SELECT id, user_id, password_hash
        FROM user_credentials
        WHERE user_id = #{userId}
        """)
    UserCredentialRecordEntity selectByUserId(@Param("userId") Long userId);
}
