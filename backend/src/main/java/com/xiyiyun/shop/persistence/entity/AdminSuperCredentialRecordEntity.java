package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 批次7 / 任务A：超管凭据（原 system_settings 的 admin.super.* 三个标量 key）。
 * 单行表，id 固定为 1。
 */
@TableName("admin_super_credentials")
public class AdminSuperCredentialRecordEntity {
    @TableId
    private Integer id;
    private String username;
    private String nickname;
    private String passwordHash;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
