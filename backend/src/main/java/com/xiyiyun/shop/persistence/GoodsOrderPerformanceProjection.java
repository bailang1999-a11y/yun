package com.xiyiyun.shop.persistence;

public class GoodsOrderPerformanceProjection {
    private Long goodsId;
    private Long averageRechargeDurationSeconds;
    private Integer todaySuccessRatePercentage;

    public Long getGoodsId() { return goodsId; }
    public void setGoodsId(Long goodsId) { this.goodsId = goodsId; }
    public Long getAverageRechargeDurationSeconds() { return averageRechargeDurationSeconds; }
    public void setAverageRechargeDurationSeconds(Long value) { this.averageRechargeDurationSeconds = value; }
    public Integer getTodaySuccessRatePercentage() { return todaySuccessRatePercentage; }
    public void setTodaySuccessRatePercentage(Integer value) { this.todaySuccessRatePercentage = value; }
}
