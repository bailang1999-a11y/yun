package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@TableName("suppliers")
public class SupplierRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String platformType;
    private String baseUrl;
    private String appKey;
    private String appSecretMasked;
    private String userId;
    private String appId;
    private String apiKey;
    private byte[] apiKeyCiphertext;
    private byte[] apiKeyNonce;
    private String apiKeyKeyVersion;
    private String apiKeyHash;
    private String apiKeyMasked;
    private String callbackUrl;
    private Integer timeoutSeconds;
    private BigDecimal balance;
    private String status;
    private String remark;
    private OffsetDateTime lastSyncAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getAppSecretMasked() { return appSecretMasked; }
    public void setAppSecretMasked(String appSecretMasked) { this.appSecretMasked = appSecretMasked; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public byte[] getApiKeyCiphertext() { return apiKeyCiphertext; }
    public void setApiKeyCiphertext(byte[] apiKeyCiphertext) { this.apiKeyCiphertext = apiKeyCiphertext; }
    public byte[] getApiKeyNonce() { return apiKeyNonce; }
    public void setApiKeyNonce(byte[] apiKeyNonce) { this.apiKeyNonce = apiKeyNonce; }
    public String getApiKeyKeyVersion() { return apiKeyKeyVersion; }
    public void setApiKeyKeyVersion(String apiKeyKeyVersion) { this.apiKeyKeyVersion = apiKeyKeyVersion; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
