package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@TableName("supplier_price_history")
public class SupplierPriceHistoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goodsId;
    private Long channelId;
    private Long supplierId;
    private String supplierName;
    private String supplierGoodsId;
    private BigDecimal unitPrice;
    private BigDecimal previousUnitPrice;
    private BigDecimal changeAmount;
    private String direction;
    private OffsetDateTime observedAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getSupplierGoodsId() { return supplierGoodsId; }
    public void setSupplierGoodsId(String supplierGoodsId) { this.supplierGoodsId = supplierGoodsId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getPreviousUnitPrice() { return previousUnitPrice; }
    public void setPreviousUnitPrice(BigDecimal previousUnitPrice) { this.previousUnitPrice = previousUnitPrice; }
    public BigDecimal getChangeAmount() { return changeAmount; }
    public void setChangeAmount(BigDecimal changeAmount) { this.changeAmount = changeAmount; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(OffsetDateTime observedAt) { this.observedAt = observedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
