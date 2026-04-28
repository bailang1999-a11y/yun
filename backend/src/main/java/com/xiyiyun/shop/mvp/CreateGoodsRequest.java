package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.util.List;

public record CreateGoodsRequest(
    Long categoryId,
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
    String status,
    List<String> tags,
    List<String> availablePlatforms,
    List<String> forbiddenPlatforms
) {
}
