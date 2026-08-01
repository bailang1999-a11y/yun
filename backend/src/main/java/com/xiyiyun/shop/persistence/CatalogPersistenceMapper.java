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
        entity.setIcon(category.icon());
        entity.setIconUrl(category.iconUrl());
        entity.setCustomIconUrl(category.customIconUrl());
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

    /**
     * 会员落库映射。
     *
     * <p><b>mobile / email 必须把空值写成 NULL，不能写空串。</b>
     * users 表上有 {@code uk_users_mobile} 与 {@code uk_users_email} 两个唯一键，
     * 而 MySQL 唯一索引<b>允许多行 NULL、不允许多行同值空串</b>。
     *
     * <p>写空串会造成如下真实故障（已在生产复现）：手机号注册的会员 email 为空，
     * 第一个会员落库后 {@code email=''} 占住了 {@code uk_users_email}；
     * 第二个手机注册会员再落库时撞上该唯一键，触发
     * {@code UserRecordMapper.upsertSnapshot} 的 {@code ON DUPLICATE KEY UPDATE}，
     * 把<b>已有那一行</b>的手机号、昵称、余额全部覆盖成新会员的值；
     * 而 {@code id} 不在 UPDATE 列表里，行号始终停在第一个会员的 id。
     * 净效果是整张 users 表只能存下一个手机注册会员，
     * 后来者不仅存不进去，还会顶掉前者的资料与余额。
     *
     * <p>连带后果：会员在库里不存在 →
     * {@code FundsLedgerStore.lockUser} 的 {@code SELECT ... FOR UPDATE} 锁不到行 →
     * 外部支付回调落账抛 {@code user not found}，钱已收到却记不上账。
     */
    public UserRecordEntity toUserRecord(UserItem user) {
        UserRecordEntity entity = new UserRecordEntity();
        entity.setId(user.id());
        entity.setAvatar(user.avatar());
        entity.setMobile(blankToNull(user.mobile()));
        entity.setEmail(blankToNull(user.email()));
        entity.setUsername(blankToNull(user.username()));
        entity.setNickname(user.nickname());
        entity.setGroupId(user.groupId());
        entity.setBalance(user.balance() == null ? BigDecimal.ZERO : user.balance());
        entity.setDeposit(user.deposit() == null ? BigDecimal.ZERO : user.deposit());
        entity.setRealNameType(user.realNameType());
        entity.setRealName(user.realName());
        entity.setSubjectName(user.subjectName());
        entity.setCertificateNo(user.certificateNo());
        entity.setVerificationStatus(user.verificationStatus());
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
            entity.getIcon(),
            textValue(entity.getIconUrl()),
            textValue(entity.getCustomIconUrl()),
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
            availablePlatforms,
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
            entity.getDeposit() == null ? BigDecimal.ZERO : entity.getDeposit(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getLastLoginAt(),
            textValue(entity.getRealNameType()).isBlank() ? "NONE" : entity.getRealNameType(),
            textValue(entity.getRealName()),
            textValue(entity.getSubjectName()),
            textValue(entity.getCertificateNo()),
            textValue(entity.getVerificationStatus()).isBlank() ? "UNVERIFIED" : entity.getVerificationStatus(),
            entity.getUsername()
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

    /**
     * 空值归一成 NULL，供带唯一索引的可选列使用。
     *
     * <p>见 {@link #toUserRecord} 上的说明：唯一索引下 {@code ''} 与 {@code NULL}
     * 语义完全不同——前者是一个会互相冲突的真实值，后者可重复。
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
