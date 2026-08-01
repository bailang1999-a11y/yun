package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 批次7 / 任务A：短信登录配置（原 system_settings 的 sms.login.setting）。单行表。
 */
@TableName("sms_login_settings")
public class SmsLoginSettingRecordEntity {
    @TableId
    private Integer id;
    private Boolean enabled;
    private Boolean adminLoginEnabled;
    private Boolean h5LoginEnabled;
    private Boolean webLoginEnabled;
    private String provider;
    private String adminMobile;
    private Integer codeLength;
    private Integer ttlSeconds;
    private Integer cooldownSeconds;
    private Integer maxAttempts;
    private String genericConfigPublic;
    private String genericConfigSecrets;
    private String tencentConfigPublic;
    private String tencentConfigSecrets;
    private String aliyunConfigPublic;
    private String aliyunConfigSecrets;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getAdminLoginEnabled() { return adminLoginEnabled; }
    public void setAdminLoginEnabled(Boolean adminLoginEnabled) { this.adminLoginEnabled = adminLoginEnabled; }
    public Boolean getH5LoginEnabled() { return h5LoginEnabled; }
    public void setH5LoginEnabled(Boolean h5LoginEnabled) { this.h5LoginEnabled = h5LoginEnabled; }
    public Boolean getWebLoginEnabled() { return webLoginEnabled; }
    public void setWebLoginEnabled(Boolean webLoginEnabled) { this.webLoginEnabled = webLoginEnabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getAdminMobile() { return adminMobile; }
    public void setAdminMobile(String adminMobile) { this.adminMobile = adminMobile; }
    public Integer getCodeLength() { return codeLength; }
    public void setCodeLength(Integer codeLength) { this.codeLength = codeLength; }
    public Integer getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public Integer getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(Integer cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getGenericConfigPublic() { return genericConfigPublic; }
    public void setGenericConfigPublic(String genericConfigPublic) { this.genericConfigPublic = genericConfigPublic; }
    public String getGenericConfigSecrets() { return genericConfigSecrets; }
    public void setGenericConfigSecrets(String genericConfigSecrets) { this.genericConfigSecrets = genericConfigSecrets; }
    public String getTencentConfigPublic() { return tencentConfigPublic; }
    public void setTencentConfigPublic(String tencentConfigPublic) { this.tencentConfigPublic = tencentConfigPublic; }
    public String getTencentConfigSecrets() { return tencentConfigSecrets; }
    public void setTencentConfigSecrets(String tencentConfigSecrets) { this.tencentConfigSecrets = tencentConfigSecrets; }
    public String getAliyunConfigPublic() { return aliyunConfigPublic; }
    public void setAliyunConfigPublic(String aliyunConfigPublic) { this.aliyunConfigPublic = aliyunConfigPublic; }
    public String getAliyunConfigSecrets() { return aliyunConfigSecrets; }
    public void setAliyunConfigSecrets(String aliyunConfigSecrets) { this.aliyunConfigSecrets = aliyunConfigSecrets; }
}
