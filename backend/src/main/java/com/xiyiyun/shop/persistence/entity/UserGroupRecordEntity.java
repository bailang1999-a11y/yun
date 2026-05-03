package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("user_groups")
public class UserGroupRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    @TableField("is_default")
    private Boolean defaultGroup;
    private String status;
    private Boolean orderEnabled;
    private Boolean realNameRequiredForOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getDefaultGroup() { return defaultGroup; }
    public void setDefaultGroup(Boolean defaultGroup) { this.defaultGroup = defaultGroup; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getOrderEnabled() { return orderEnabled; }
    public void setOrderEnabled(Boolean orderEnabled) { this.orderEnabled = orderEnabled; }
    public Boolean getRealNameRequiredForOrder() { return realNameRequiredForOrder; }
    public void setRealNameRequiredForOrder(Boolean realNameRequiredForOrder) { this.realNameRequiredForOrder = realNameRequiredForOrder; }
}
