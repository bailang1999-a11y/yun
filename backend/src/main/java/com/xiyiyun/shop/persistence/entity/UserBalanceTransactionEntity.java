package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 资金流水（006 迁移建表 user_balance_transactions）。
 *
 * <p>每一次 users.balance 变动都必须留下一行，唯一键 uk_balance_tx_biz (biz_type, biz_no)
 * 是「同一笔业务只记一次账」的幂等保证。
 */
@TableName("user_balance_transactions")
public class UserBalanceTransactionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** DEBIT=扣减，CREDIT=增加。 */
    private String direction;
    /** 恒为正数，方向由 direction 决定。 */
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    /** ORDER_PAY / ORDER_REFUND / RECHARGE / ADMIN_ADJUST / PAYMENT_SETTLE。 */
    private String bizType;
    private String bizNo;
    private String remark;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
