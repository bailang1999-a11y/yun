package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 批次7 / 任务A：图形验证码配置（原 system_settings 的 captcha.setting）。单行表。
 */
@TableName("captcha_settings")
public class CaptchaSettingRecordEntity {
    @TableId
    private Integer id;
    private Boolean enabled;
    private Boolean adminLoginEnabled;
    private Boolean h5LoginEnabled;
    private Boolean webLoginEnabled;
    private String provider;
    private String tencentConfigPublic;
    private String tencentConfigSecrets;
    private String turnstileConfigPublic;
    private String turnstileConfigSecrets;
    private String genericConfigPublic;
    private String genericConfigSecrets;

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
    public String getTencentConfigPublic() { return tencentConfigPublic; }
    public void setTencentConfigPublic(String tencentConfigPublic) { this.tencentConfigPublic = tencentConfigPublic; }
    public String getTencentConfigSecrets() { return tencentConfigSecrets; }
    public void setTencentConfigSecrets(String tencentConfigSecrets) { this.tencentConfigSecrets = tencentConfigSecrets; }
    public String getTurnstileConfigPublic() { return turnstileConfigPublic; }
    public void setTurnstileConfigPublic(String turnstileConfigPublic) { this.turnstileConfigPublic = turnstileConfigPublic; }
    public String getTurnstileConfigSecrets() { return turnstileConfigSecrets; }
    public void setTurnstileConfigSecrets(String turnstileConfigSecrets) { this.turnstileConfigSecrets = turnstileConfigSecrets; }
    public String getGenericConfigPublic() { return genericConfigPublic; }
    public void setGenericConfigPublic(String genericConfigPublic) { this.genericConfigPublic = genericConfigPublic; }
    public String getGenericConfigSecrets() { return genericConfigSecrets; }
    public void setGenericConfigSecrets(String genericConfigSecrets) { this.genericConfigSecrets = genericConfigSecrets; }
}
