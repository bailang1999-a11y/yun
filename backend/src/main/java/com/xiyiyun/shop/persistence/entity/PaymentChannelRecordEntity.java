package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 批次7 / 任务A：支付通道（原 system_settings 的 payment.channels JSON 数组）。
 *
 * <p>configPublic / configSecrets 分两列：前者是可见配置，后者是加密信封。
 * 两者都以 JSON 字符串原样传递，加解密仍由上层 CardCipherService 负责。
 */
@TableName("payment_channels")
public class PaymentChannelRecordEntity {
    @TableId
    private Long id;
    private String code;
    private String name;
    private String channelType;
    private String terminals;
    private String status;
    private Integer sortNo;
    private String configPublic;
    private String configSecrets;
    private String remark;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getTerminals() { return terminals; }
    public void setTerminals(String terminals) { this.terminals = terminals; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getConfigPublic() { return configPublic; }
    public void setConfigPublic(String configPublic) { this.configPublic = configPublic; }
    public String getConfigSecrets() { return configSecrets; }
    public void setConfigSecrets(String configSecrets) { this.configSecrets = configSecrets; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
