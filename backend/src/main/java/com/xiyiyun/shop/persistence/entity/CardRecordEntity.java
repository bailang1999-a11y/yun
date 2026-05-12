package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("cards")
public class CardRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goodsId;
    private Long cardKindId;
    private String batchNo;
    private byte[] cardCiphertext;
    private byte[] cardNonce;
    private String cardKeyVersion;
    private String cardHash;
    private String cardPreview;
    private String status;
    private Long lockedOrderId;
    private Long soldOrderId;
    private OffsetDateTime soldAt;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public Long getCardKindId() { return cardKindId; }
    public void setCardKindId(Long cardKindId) { this.cardKindId = cardKindId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public byte[] getCardCiphertext() { return cardCiphertext; }
    public void setCardCiphertext(byte[] cardCiphertext) { this.cardCiphertext = cardCiphertext; }
    public byte[] getCardNonce() { return cardNonce; }
    public void setCardNonce(byte[] cardNonce) { this.cardNonce = cardNonce; }
    public String getCardKeyVersion() { return cardKeyVersion; }
    public void setCardKeyVersion(String cardKeyVersion) { this.cardKeyVersion = cardKeyVersion; }
    public String getCardHash() { return cardHash; }
    public void setCardHash(String cardHash) { this.cardHash = cardHash; }
    public String getCardPreview() { return cardPreview; }
    public void setCardPreview(String cardPreview) { this.cardPreview = cardPreview; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getLockedOrderId() { return lockedOrderId; }
    public void setLockedOrderId(Long lockedOrderId) { this.lockedOrderId = lockedOrderId; }
    public Long getSoldOrderId() { return soldOrderId; }
    public void setSoldOrderId(Long soldOrderId) { this.soldOrderId = soldOrderId; }
    public OffsetDateTime getSoldAt() { return soldAt; }
    public void setSoldAt(OffsetDateTime soldAt) { this.soldAt = soldAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
