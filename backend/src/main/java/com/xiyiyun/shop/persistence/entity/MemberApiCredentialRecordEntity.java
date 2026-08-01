package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 批次7 / 任务A：会员开放 API 凭据（原 system_settings 的 member.credential.{userId}）。
 *
 * <p>明文 appSecret 不落库：只保留掩码 + CardCipherService 的密文信封
 * （ciphertext / nonce / keyVersion / hash）。001 里的 app_secret 明文列在 007
 * 被放宽为 NULL 并保留（回滚证据），本实体不映射它。
 */
@TableName("member_api_credentials")
public class MemberApiCredentialRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String appKey;
    private String appSecretMasked;
    private String appSecretCiphertext;
    private String appSecretNonce;
    private String appSecretKeyVersion;
    private String appSecretHash;
    private String callbackUrl;
    private String status;
    private String ipWhitelist;
    private Integer dailyLimit;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getAppSecretMasked() { return appSecretMasked; }
    public void setAppSecretMasked(String appSecretMasked) { this.appSecretMasked = appSecretMasked; }
    public String getAppSecretCiphertext() { return appSecretCiphertext; }
    public void setAppSecretCiphertext(String appSecretCiphertext) { this.appSecretCiphertext = appSecretCiphertext; }
    public String getAppSecretNonce() { return appSecretNonce; }
    public void setAppSecretNonce(String appSecretNonce) { this.appSecretNonce = appSecretNonce; }
    public String getAppSecretKeyVersion() { return appSecretKeyVersion; }
    public void setAppSecretKeyVersion(String appSecretKeyVersion) { this.appSecretKeyVersion = appSecretKeyVersion; }
    public String getAppSecretHash() { return appSecretHash; }
    public void setAppSecretHash(String appSecretHash) { this.appSecretHash = appSecretHash; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIpWhitelist() { return ipWhitelist; }
    public void setIpWhitelist(String ipWhitelist) { this.ipWhitelist = ipWhitelist; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
