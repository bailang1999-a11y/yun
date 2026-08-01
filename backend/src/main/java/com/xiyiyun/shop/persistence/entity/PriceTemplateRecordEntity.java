package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/**
 * 批次7 / 任务A：价格模板（原 system_settings 的 price.templates JSON 数组）。
 *
 * <p>主键是业务自带的字符串模板 id；groupRates 以 JSON 字符串原样传递。
 */
@TableName("price_templates")
public class PriceTemplateRecordEntity {
    @TableId
    private String templateId;
    private String name;
    private String adjustMode;
    private BigDecimal referencePrice;
    private String groupRates;
    private Boolean enabled;
    private Integer sortNo;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAdjustMode() { return adjustMode; }
    public void setAdjustMode(String adjustMode) { this.adjustMode = adjustMode; }
    public BigDecimal getReferencePrice() { return referencePrice; }
    public void setReferencePrice(BigDecimal referencePrice) { this.referencePrice = referencePrice; }
    public String getGroupRates() { return groupRates; }
    public void setGroupRates(String groupRates) { this.groupRates = groupRates; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
