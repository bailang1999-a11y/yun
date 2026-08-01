package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.MemberApiCredentialRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批次7 / 任务A：会员开放 API 凭据（原 system_settings 的 member.credential.{userId}）。
 *
 * <p>upsert 以 user_id 为冲突键（007 建了 uk_member_api_user）。KV 里的 {@code id}
 * 字段不再作为身份：它是内存自增序列派生的，不同用户可能拿到同一个值。
 */
@Mapper
public interface MemberApiCredentialRecordMapper extends BaseMapper<MemberApiCredentialRecordEntity> {
    @Update("""
        INSERT INTO member_api_credentials (
            user_id, app_key, app_secret, app_secret_masked, app_secret_ciphertext,
            app_secret_nonce, app_secret_key_version, app_secret_hash,
            callback_url, status, ip_whitelist, daily_limit, last_used_at, created_at, updated_at
        ) VALUES (
            #{entity.userId}, #{entity.appKey}, NULL, #{entity.appSecretMasked}, #{entity.appSecretCiphertext},
            #{entity.appSecretNonce}, #{entity.appSecretKeyVersion}, #{entity.appSecretHash},
            #{entity.callbackUrl}, #{entity.status}, #{entity.ipWhitelist}, #{entity.dailyLimit}, #{entity.lastUsedAt},
            COALESCE(#{entity.createdAt}, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3)
        )
        ON DUPLICATE KEY UPDATE
            app_key = VALUES(app_key),
            app_secret = NULL,
            app_secret_masked = VALUES(app_secret_masked),
            app_secret_ciphertext = VALUES(app_secret_ciphertext),
            app_secret_nonce = VALUES(app_secret_nonce),
            app_secret_key_version = VALUES(app_secret_key_version),
            app_secret_hash = VALUES(app_secret_hash),
            callback_url = VALUES(callback_url),
            status = VALUES(status),
            ip_whitelist = VALUES(ip_whitelist),
            daily_limit = VALUES(daily_limit),
            last_used_at = VALUES(last_used_at),
            updated_at = CURRENT_TIMESTAMP(3)
        """)
    int upsertSnapshot(@Param("entity") MemberApiCredentialRecordEntity entity);

    @Select("""
        SELECT id, user_id, app_key, app_secret_masked, app_secret_ciphertext,
               app_secret_nonce, app_secret_key_version, app_secret_hash,
               callback_url, status, ip_whitelist, daily_limit, last_used_at, created_at
        FROM member_api_credentials
        WHERE user_id = #{userId}
        """)
    MemberApiCredentialRecordEntity selectByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT id, user_id, app_key, app_secret_masked, app_secret_ciphertext,
               app_secret_nonce, app_secret_key_version, app_secret_hash,
               callback_url, status, ip_whitelist, daily_limit, last_used_at, created_at
        FROM member_api_credentials
        WHERE app_key = #{appKey}
        """)
    MemberApiCredentialRecordEntity selectByAppKey(@Param("appKey") String appKey);
}
