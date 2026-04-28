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
    List<String> detailImages,
    List<GoodsDetailBlock> detailBlocks,
    GoodsType type,
    String platform,
    BigDecimal price,
    BigDecimal originalPrice,
    Integer maxBuy,
    Boolean requireRechargeAccount,
    List<String> accountTypes,
    String priceTemplateId,
    String priceMode,
    BigDecimal priceCoefficient,
    BigDecimal priceFixedAdd,
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
            detailImages,
            detailBlocks,
            type,
            platform,
            price,
            originalPrice,
            maxBuy,
            requireRechargeAccount,
            accountTypes,
            priceTemplateId,
            priceMode,
            priceCoefficient,
            priceFixedAdd,
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
