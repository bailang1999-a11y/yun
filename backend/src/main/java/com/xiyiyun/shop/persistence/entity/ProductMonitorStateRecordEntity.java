package com.xiyiyun.shop.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 批次7 / 任务A：商品监控扫描状态（原 system_settings 的 product.monitor.state.{id}）。
 *
 * <p>不含 scanning 列：它是进程内瞬时标记，重启后必须归 false，
 * 否则该渠道永远不会再被判定到期。
 */
@TableName("product_monitor_states")
public class ProductMonitorStateRecordEntity {
    @TableId
    private Long channelId;
    private OffsetDateTime lastScanAt;
    private OffsetDateTime nextScanAt;
    private String lastResult;
    private String lastMessage;
    private Integer scanCount;
    private Integer changeCount;

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public OffsetDateTime getLastScanAt() { return lastScanAt; }
    public void setLastScanAt(OffsetDateTime lastScanAt) { this.lastScanAt = lastScanAt; }
    public OffsetDateTime getNextScanAt() { return nextScanAt; }
    public void setNextScanAt(OffsetDateTime nextScanAt) { this.nextScanAt = nextScanAt; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public Integer getScanCount() { return scanCount; }
    public void setScanCount(Integer scanCount) { this.scanCount = scanCount; }
    public Integer getChangeCount() { return changeCount; }
    public void setChangeCount(Integer changeCount) { this.changeCount = changeCount; }
}
