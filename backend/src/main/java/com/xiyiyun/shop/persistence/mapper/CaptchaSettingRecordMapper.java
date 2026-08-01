package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.CaptchaSettingRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：图形验证码配置（原 system_settings 的 captcha.setting）。单行表。
 */
@Mapper
public interface CaptchaSettingRecordMapper extends BaseMapper<CaptchaSettingRecordEntity> {
    @Update("""
        INSERT INTO captcha_settings (
            id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled, provider,
            tencent_config_public, tencent_config_secrets,
            turnstile_config_public, turnstile_config_secrets,
            generic_config_public, generic_config_secrets
        ) VALUES (
            1, #{entity.enabled}, #{entity.adminLoginEnabled}, #{entity.h5LoginEnabled}, #{entity.webLoginEnabled},
            #{entity.provider},
            #{entity.tencentConfigPublic}, #{entity.tencentConfigSecrets},
            #{entity.turnstileConfigPublic}, #{entity.turnstileConfigSecrets},
            #{entity.genericConfigPublic}, #{entity.genericConfigSecrets}
        )
        ON DUPLICATE KEY UPDATE
            enabled = VALUES(enabled),
            admin_login_enabled = VALUES(admin_login_enabled),
            h5_login_enabled = VALUES(h5_login_enabled),
            web_login_enabled = VALUES(web_login_enabled),
            provider = VALUES(provider),
            tencent_config_public = VALUES(tencent_config_public),
            tencent_config_secrets = VALUES(tencent_config_secrets),
            turnstile_config_public = VALUES(turnstile_config_public),
            turnstile_config_secrets = VALUES(turnstile_config_secrets),
            generic_config_public = VALUES(generic_config_public),
            generic_config_secrets = VALUES(generic_config_secrets)
        """)
    int upsertSingleton(@Param("entity") CaptchaSettingRecordEntity entity);

    @Select("""
        SELECT id, enabled, admin_login_enabled, h5_login_enabled, web_login_enabled, provider,
               tencent_config_public, tencent_config_secrets,
               turnstile_config_public, turnstile_config_secrets,
               generic_config_public, generic_config_secrets
        FROM captcha_settings
        WHERE id = 1
        """)
    CaptchaSettingRecordEntity selectSingleton();
}
