package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 批次7 / 任务A：后台员工账号（原 system_settings 的 admin.staff.accounts JSON 数组）。
 *
 * <p>permissions 保持 JSON 字符串原样传递，序列化/反序列化由 ConfigService 负责，
 * 持久化层不解释权限语义。
 */
@TableName("admin_staff")
public class AdminStaffRecordEntity {
    @TableId
    private Long id;
    private String account;
    private String nickname;
    private String status;
    private String permissions;
    private String passwordHash;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
