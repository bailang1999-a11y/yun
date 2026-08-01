package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record GoodsListItem(
    Long id,
    Long categoryId,
    String categoryName,
    String goodsName,
    String name,
    String subTitle,
    List<String> benefitDurations,
    String benefitType,
    String benefitBrand,
    Boolean priceLimited,
    String priceLimitText,
    String coverUrl,
    List<GoodsIntegrationItem> integrations,
    Boolean pollingEnabled,
    Boolean monitoringEnabled,
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
    List<String> forbiddenPlatforms,
    Long cardKindId
) {
    public static GoodsListItem from(GoodsItem item) {
        return new GoodsListItem(
            item.id(),
            item.categoryId(),
            item.categoryName(),
            item.goodsName(),
            item.name(),
            item.subTitle(),
            item.benefitDurations(),
            item.benefitType(),
            item.benefitBrand(),
            item.priceLimited(),
            item.priceLimitText(),
            item.coverUrl(),
            item.integrations(),
            item.pollingEnabled(),
            item.monitoringEnabled(),
            item.type(),
            item.platform(),
            item.price(),
            item.originalPrice(),
            item.maxBuy(),
            item.requireRechargeAccount(),
            item.accountTypes(),
            item.priceTemplateId(),
            item.priceMode(),
            item.priceCoefficient(),
            item.priceFixedAdd(),
            item.stock(),
            item.sales(),
            item.status(),
            item.tags(),
            item.createdAt(),
            item.updatedAt(),
            item.availablePlatforms(),
            item.forbiddenPlatforms(),
            item.cardKindId()
        );
    }
}
