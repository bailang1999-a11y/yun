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
    GoodsType type,
    String platform,
    BigDecimal price,
    BigDecimal originalPrice,
    Integer stock,
    String status,
    List<String> tags,
    List<String> availablePlatforms,
    List<String> forbiddenPlatforms
) {
}
