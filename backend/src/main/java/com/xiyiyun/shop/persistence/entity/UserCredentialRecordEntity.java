package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 批次7 / 任务A：会员登录口令哈希（原 system_settings 的 user.password.{userId}）。
 *
 * <p>只承载 bcrypt 哈希，不存明文，也不存任何可反推口令的信息。
 */
@TableName("user_credentials")
public class UserCredentialRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String passwordHash;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
