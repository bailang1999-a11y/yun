package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record GoodsItem(
    Long id,
    Long categoryId,
    String categoryName,
    String goodsName,
    String name,
    String subTitle,
    String description,
    String coverUrl,
    GoodsType type,
    String platform,
    BigDecimal price,
    BigDecimal originalPrice,
    Integer stock,
    Integer sales,
    String status,
    List<String> tags,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<String> availablePlatforms,
    List<String> forbiddenPlatforms
) {
    public GoodsItem withStock(Integer nextStock) {
        return new GoodsItem(
            id,
            categoryId,
            categoryName,
            goodsName,
            name,
            subTitle,
            description,
            coverUrl,
            type,
            platform,
            price,
            originalPrice,
            nextStock,
            sales,
            status,
            tags,
            createdAt,
            OffsetDateTime.now(),
            availablePlatforms,
            forbiddenPlatforms
        );
    }
}
