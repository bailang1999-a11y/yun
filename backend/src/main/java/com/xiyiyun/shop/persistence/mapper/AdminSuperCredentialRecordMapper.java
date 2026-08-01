package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.AdminSuperCredentialRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：超管凭据（原 system_settings 的 admin.super.username / nickname / passwordHash）。
 *
 * <p>三个 key 拆成三行 KV 时无法原子更新：改用户名成功、改口令失败会留下半套凭据。
 * 单行表让三者要么一起生效要么一起不生效。
 */
@Mapper
public interface AdminSuperCredentialRecordMapper extends BaseMapper<AdminSuperCredentialRecordEntity> {
    /** 单行表固定主键，见 007 迁移。 */
    int SINGLETON_ID = 1;

    @Update("""
        INSERT INTO admin_super_credentials (id, username, nickname, password_hash)
        VALUES (1, #{username}, #{nickname}, #{passwordHash})
        ON DUPLICATE KEY UPDATE
            username = VALUES(username),
            nickname = VALUES(nickname),
            password_hash = VALUES(password_hash)
        """)
    int upsertSingleton(
        @Param("username") String username,
        @Param("nickname") String nickname,
        @Param("passwordHash") String passwordHash
    );

    @Select("""
        SELECT id, username, nickname, password_hash
        FROM admin_super_credentials
        WHERE id = 1
        """)
    AdminSuperCredentialRecordEntity selectSingleton();
}
