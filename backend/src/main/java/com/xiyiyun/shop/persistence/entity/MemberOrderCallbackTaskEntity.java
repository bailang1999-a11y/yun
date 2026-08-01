package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("member_order_callback_tasks")
public class MemberOrderCallbackTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long userId;
    private String requestId;
    private String orderNo;
    private String eventType;
    private String orderStatus;
    private String callbackUrl;
    private String payloadJson;
    private byte[] sensitiveCiphertext;
    private byte[] sensitiveNonce;
    private String sensitiveKeyVersion;
    private String state;
    private Integer attemptCount;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime leaseUntil;
    private String lastError;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public byte[] getSensitiveCiphertext() { return sensitiveCiphertext; }
    public void setSensitiveCiphertext(byte[] sensitiveCiphertext) { this.sensitiveCiphertext = sensitiveCiphertext; }
    public byte[] getSensitiveNonce() { return sensitiveNonce; }
    public void setSensitiveNonce(byte[] sensitiveNonce) { this.sensitiveNonce = sensitiveNonce; }
    public String getSensitiveKeyVersion() { return sensitiveKeyVersion; }
    public void setSensitiveKeyVersion(String sensitiveKeyVersion) { this.sensitiveKeyVersion = sensitiveKeyVersion; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
