package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.SmsLoginSettingRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：短信登录配置（原 system_settings 的 sms.login.setting）。单行表。
 */
@Mapper
public interface SmsLoginSettingRecordMapper extends BaseMapper<SmsLoginSettingRecordEntity> {
    @Update("""
        INSERT INTO sms_login_settings (
            id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled, provider, admin_mobile,
            code_length, ttl_seconds, cooldown_seconds, max_attempts,
            generic_config_public, generic_config_secrets,
            tencent_config_public, tencent_config_secrets,
            aliyun_config_public, aliyun_config_secrets
        ) VALUES (
            1, #{entity.enabled}, #{entity.adminLoginEnabled}, #{entity.h5LoginEnabled}, #{entity.webLoginEnabled},
            #{entity.provider}, #{entity.adminMobile}, #{entity.codeLength}, #{entity.ttlSeconds},
            #{entity.cooldownSeconds}, #{entity.maxAttempts},
            #{entity.genericConfigPublic}, #{entity.genericConfigSecrets},
            #{entity.tencentConfigPublic}, #{entity.tencentConfigSecrets},
            #{entity.aliyunConfigPublic}, #{entity.aliyunConfigSecrets}
        )
        ON DUPLICATE KEY UPDATE
            enabled = VALUES(enabled),
            admin_login_enabled = VALUES(admin_login_enabled),
            h5_login_enabled = VALUES(h5_login_enabled),
            web_login_enabled = VALUES(web_login_enabled),
            provider = VALUES(provider),
            admin_mobile = VALUES(admin_mobile),
            code_length = VALUES(code_length),
            ttl_seconds = VALUES(ttl_seconds),
            cooldown_seconds = VALUES(cooldown_seconds),
            max_attempts = VALUES(max_attempts),
            generic_config_public = VALUES(generic_config_public),
            generic_config_secrets = VALUES(generic_config_secrets),
            tencent_config_public = VALUES(tencent_config_public),
            tencent_config_secrets = VALUES(tencent_config_secrets),
            aliyun_config_public = VALUES(aliyun_config_public),
            aliyun_config_secrets = VALUES(aliyun_config_secrets)
        """)
    int upsertSingleton(@Param("entity") SmsLoginSettingRecordEntity entity);

    @Select("""
        SELECT id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled, provider, admin_mobile,
               code_length, ttl_seconds, cooldown_seconds, max_attempts,
               generic_config_public, generic_config_secrets,
               tencent_config_public, tencent_config_secrets,
               aliyun_config_public, aliyun_config_secrets
        FROM sms_login_settings
        WHERE id = 1
        """)
    SmsLoginSettingRecordEntity selectSingleton();
}
