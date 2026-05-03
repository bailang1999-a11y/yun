package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.UserItem;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import java.math.BigDecimal;
import java.util.List;

public class CatalogPersistenceMapper {
    public CategoryRecordEntity toCategoryRecord(CategoryItem category) {
        CategoryRecordEntity entity = new CategoryRecordEntity();
        entity.setId(category.id());
        entity.setParentId(category.parentId() == null || category.parentId() == 0L ? null : category.parentId());
        entity.setName(category.name());
        entity.setSortNo(category.sort());
        entity.setStatus(Boolean.FALSE.equals(category.enabled()) ? "OFF_SALE" : "ON_SALE");
        return entity;
    }

    public GoodsRecordEntity toGoodsRecord(GoodsItem goods) {
        GoodsRecordEntity entity = new GoodsRecordEntity();
        entity.setId(goods.id());
        entity.setCategoryId(goods.categoryId());
        entity.setName(goods.goodsName());
        entity.setGoodsType(goods.type() == null ? GoodsType.CARD.name() : goods.type().name());
        entity.setStatus(goods.status());
        entity.setFaceValue(goods.originalPrice());
        entity.setSalePrice(goods.price() == null ? BigDecimal.ZERO : goods.price());
        entity.setCostPrice(BigDecimal.ZERO);
        entity.setStockMode(goods.type() == GoodsType.DIRECT ? "REMOTE" : "LOCAL");
        entity.setStockCount(goods.stock() == null ? 0 : goods.stock());
        entity.setMinQty(1);
        entity.setMaxQty(goods.maxBuy());
        entity.setDeliveryTemplate(null);
        entity.setSortNo(0);
        entity.setDescription(goods.description());
        entity.setCreatedAt(goods.createdAt());
        return entity;
    }

    public UserRecordEntity toUserRecord(UserItem user) {
        UserRecordEntity entity = new UserRecordEntity();
        entity.setId(user.id());
        entity.setAvatar(user.avatar());
        entity.setMobile(user.mobile());
        entity.setEmail(user.email());
        entity.setNickname(user.nickname());
        entity.setGroupId(user.groupId());
        entity.setBalance(user.balance() == null ? BigDecimal.ZERO : user.balance());
        entity.setStatus(user.status());
        entity.setLastLoginAt(user.lastLoginAt());
        entity.setCreatedAt(user.createdAt());
        return entity;
    }

    public CategoryItem toCategoryItem(CategoryRecordEntity entity, int level, boolean hasChildren) {
        boolean enabled = !"OFF_SALE".equals(entity.getStatus()) && !"DISABLED".equals(entity.getStatus());
        return new CategoryItem(
            entity.getId(),
            entity.getName(),
            "",
            entity.getParentId() == null ? 0L : entity.getParentId(),
            null,
            "",
            "",
            entity.getSortNo(),
            enabled,
            enabled ? "ENABLED" : "DISABLED",
            level,
            hasChildren
        );
    }

    public GoodsItem toGoodsItem(GoodsRecordEntity entity, String categoryName) {
        GoodsType type = parseGoodsType(entity.getGoodsType());
        return new GoodsItem(
            entity.getId(),
            entity.getCategoryId(),
            categoryName,
            entity.getName(),
            entity.getName(),
            "",
            entity.getDescription(),
            List.of(),
            "",
            List.of(),
            List.of(),
            List.of(),
            false,
            false,
            type,
            "GENERAL",
            entity.getSalePrice(),
            entity.getFaceValue(),
            entity.getMaxQty(),
            false,
            List.of(),
            "retail-default",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            entity.getStockCount(),
            0,
            entity.getStatus(),
            List.of(),
            entity.getCreatedAt(),
            entity.getCreatedAt(),
            List.of("GENERAL"),
            List.of(),
            null
        );
    }

    public UserItem toUserItem(UserRecordEntity entity) {
        return new UserItem(
            entity.getId(),
            entity.getAvatar(),
            entity.getMobile(),
            entity.getEmail(),
            entity.getNickname(),
            entity.getGroupId(),
            "",
            entity.getBalance(),
            BigDecimal.ZERO,
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getLastLoginAt(),
            "NONE",
            "",
            "",
            "",
            "UNVERIFIED"
        );
    }

    private GoodsType parseGoodsType(String value) {
        try {
            return value == null ? GoodsType.CARD : GoodsType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return GoodsType.CARD;
        }
    }
}
