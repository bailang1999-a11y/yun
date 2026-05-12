package com.xiyiyun.shop.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.CategoryItem;
import com.xiyiyun.shop.mvp.GoodsDetailBlock;
import com.xiyiyun.shop.mvp.GoodsIntegrationItem;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.UserItem;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CatalogPersistenceMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> LEGACY_SYSTEM_GOODS_TAGS = Set.of("new", "api-source");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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
        entity.setDeliveryTemplate(toDeliveryTemplate(goods));
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
        Map<String, Object> deliveryTemplate = parseDeliveryTemplate(entity.getDeliveryTemplate());
        String subTitle = textValue(deliveryTemplate.get("subTitle"));
        List<String> benefitDurations = stringList(deliveryTemplate.get("benefitDurations"));
        String benefitType = textValue(deliveryTemplate.get("benefitType"));
        String benefitBrand = textValue(deliveryTemplate.get("benefitBrand"));
        String priceLimitText = textValue(deliveryTemplate.get("priceLimitText")).trim();
        boolean priceLimited = !priceLimitText.isBlank() || booleanValue(deliveryTemplate.get("priceLimited"), false);
        if (priceLimitText.isBlank() && priceLimited) {
            priceLimitText = "限价";
        }
        String coverUrl = textValue(deliveryTemplate.get("coverUrl"));
        List<String> detailImages = stringList(deliveryTemplate.get("detailImages"));
        List<GoodsDetailBlock> detailBlocks = detailBlocks(deliveryTemplate.get("detailBlocks"));
        List<GoodsIntegrationItem> integrations = integrations(deliveryTemplate.get("integrations"));
        boolean pollingEnabled = booleanValue(deliveryTemplate.get("pollingEnabled"), false);
        boolean monitoringEnabled = booleanValue(deliveryTemplate.get("monitoringEnabled"), false);
        boolean requireRechargeAccount = booleanValue(deliveryTemplate.get("requireRechargeAccount"), false);
        List<String> accountTypes = stringList(deliveryTemplate.get("accountTypes"));
        String priceTemplateId = textValue(deliveryTemplate.get("priceTemplateId"));
        String priceMode = textValue(deliveryTemplate.get("priceMode"));
        BigDecimal priceCoefficient = decimalValue(deliveryTemplate.get("priceCoefficient"), BigDecimal.ONE);
        BigDecimal priceFixedAdd = decimalValue(deliveryTemplate.get("priceFixedAdd"), BigDecimal.ZERO);
        List<String> tags = goodsTags(deliveryTemplate.get("tags"));
        List<String> availablePlatforms = stringList(deliveryTemplate.get("availablePlatforms"));
        List<String> forbiddenPlatforms = stringList(deliveryTemplate.get("forbiddenPlatforms"));
        Long cardKindId = longValue(deliveryTemplate.get("cardKindId"));
        return new GoodsItem(
            entity.getId(),
            entity.getCategoryId(),
            categoryName,
            entity.getName(),
            entity.getName(),
            subTitle,
            entity.getDescription(),
            benefitDurations,
            benefitType,
            benefitBrand,
            priceLimited,
            priceLimitText,
            coverUrl,
            detailImages,
            detailBlocks,
            integrations,
            pollingEnabled,
            monitoringEnabled,
            type,
            "GENERAL",
            entity.getSalePrice(),
            entity.getFaceValue(),
            entity.getMaxQty(),
            requireRechargeAccount,
            accountTypes,
            priceTemplateId.isBlank() ? "retail-default" : priceTemplateId,
            priceMode.isBlank() ? "FIXED" : priceMode,
            priceCoefficient,
            priceFixedAdd,
            entity.getStockCount(),
            0,
            entity.getStatus(),
            tags,
            entity.getCreatedAt(),
            entity.getCreatedAt(),
            availablePlatforms.isEmpty() ? List.of("GENERAL") : availablePlatforms,
            forbiddenPlatforms,
            cardKindId
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

    private String toDeliveryTemplate(GoodsItem goods) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("subTitle", goods.subTitle() == null ? "" : goods.subTitle());
        media.put("benefitDurations", goods.benefitDurations() == null ? List.of() : goods.benefitDurations());
        media.put("benefitType", goods.benefitType() == null ? "" : goods.benefitType());
        media.put("benefitBrand", goods.benefitBrand() == null ? "" : goods.benefitBrand());
        media.put("priceLimited", goods.priceLimitText() != null && !goods.priceLimitText().isBlank());
        media.put("priceLimitText", goods.priceLimitText() == null ? "" : goods.priceLimitText());
        media.put("coverUrl", goods.coverUrl() == null ? "" : goods.coverUrl());
        media.put("detailImages", goods.detailImages() == null ? List.of() : goods.detailImages());
        media.put("detailBlocks", goods.detailBlocks() == null ? List.of() : goods.detailBlocks());
        media.put("integrations", goods.integrations() == null ? List.of() : goods.integrations());
        media.put("pollingEnabled", Boolean.TRUE.equals(goods.pollingEnabled()));
        media.put("monitoringEnabled", goods.monitoringEnabled() == null || goods.monitoringEnabled());
        media.put("requireRechargeAccount", Boolean.TRUE.equals(goods.requireRechargeAccount()));
        media.put("accountTypes", goods.accountTypes() == null ? List.of() : goods.accountTypes());
        media.put("priceTemplateId", goods.priceTemplateId() == null ? "" : goods.priceTemplateId());
        media.put("priceMode", goods.priceMode() == null ? "" : goods.priceMode());
        media.put("priceCoefficient", goods.priceCoefficient() == null ? BigDecimal.ONE : goods.priceCoefficient());
        media.put("priceFixedAdd", goods.priceFixedAdd() == null ? BigDecimal.ZERO : goods.priceFixedAdd());
        media.put("tags", goodsTags(goods.tags()));
        media.put("availablePlatforms", goods.availablePlatforms() == null ? List.of() : goods.availablePlatforms());
        media.put("forbiddenPlatforms", goods.forbiddenPlatforms() == null ? List.of() : goods.forbiddenPlatforms());
        media.put("cardKindId", goods.cardKindId());
        try {
            return OBJECT_MAPPER.writeValueAsString(media);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, Object> parseDeliveryTemplate(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::textValue)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private List<String> goodsTags(Object value) {
        return stringList(value).stream()
            .filter(item -> !LEGACY_SYSTEM_GOODS_TAGS.contains(item.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private BigDecimal decimalValue(Object value, BigDecimal fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<GoodsDetailBlock> detailBlocks(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<?, ?>) item)
            .map(item -> new GoodsDetailBlock(
                textValue(item.get("type")),
                textValue(item.get("imageUrl")),
                textValue(item.get("text"))
            ))
            .filter(item -> !item.imageUrl().isBlank() || !item.text().isBlank())
            .toList();
    }

    private List<GoodsIntegrationItem> integrations(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> integration((Map<?, ?>) item))
            .filter(item -> !item.platformCode().isBlank() || !item.supplierGoodsId().isBlank())
            .toList();
    }

    private GoodsIntegrationItem integration(Map<?, ?> value) {
        Long supplierId = longValue(value.get("supplierId"));
        String supplierGoodsId = textValue(value.get("supplierGoodsId"));
        String id = textValue(value.get("id"));
        return new GoodsIntegrationItem(
            id.isBlank() ? "integration-" + (supplierId == null ? "" : supplierId) + "-" + supplierGoodsId : id,
            supplierId,
            textValue(value.get("supplierName")),
            textValue(value.get("platformCode")),
            supplierGoodsId,
            textValue(value.get("supplierGoodsName")),
            decimalValue(value.get("supplierPrice"), BigDecimal.ZERO),
            textValue(value.get("upstreamStatus")).isBlank() ? "正常" : textValue(value.get("upstreamStatus")),
            intValue(value.get("upstreamStock"), 0),
            textValue(value.get("upstreamTitle")),
            textValue(value.get("lastSyncAt")),
            booleanValue(value.get("enabled"), true)
        );
    }

    private Integer intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
