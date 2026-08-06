package com.xiyiyun.shop.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.PageSlice;
import com.xiyiyun.shop.mvp.RejectedOrderItem;
import com.xiyiyun.shop.persistence.entity.AgisoRejectedOrderEntity;
import com.xiyiyun.shop.persistence.mapper.AgisoRejectedOrderMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgisoRejectedOrderStore {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final AgisoRejectedOrderMapper mapper;

    public AgisoRejectedOrderStore(AgisoRejectedOrderMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void record(Long userId, String buyerAccount, Map<String, Object> payload, GoodsItem goods,
                       GoodsType requestedType,
                       BigDecimal expectedAmount, String rechargeAccount, Map<String, String> rechargeFields,
                       String rejectCode, String rejectReason) {
        String externalOrderNo = text(payload.get("orderNo"));
        if (userId == null || externalOrderNo.isEmpty()) return;
        Long productNo = longValue(payload.get("productNo"));
        Integer quantity = intValue(payload.get("buyNum"), 1);
        mapper.upsert(
            displayOrderNo(userId, externalOrderNo), userId, clean(buyerAccount), externalOrderNo,
            productNo, goods == null ? "未知商品" : clean(goods.goodsName()),
            goods == null ? requestedType.name() : goods.type().name(), quantity,
            decimal(payload.get("maxAmount")), expectedAmount, clean(rechargeAccount), json(rechargeFields),
            text(payload.get("callbackUrl")), clean(rejectCode), clean(rejectReason), OffsetDateTime.now()
        );
    }

    @Transactional
    public void resolve(Long userId, String externalOrderNo, String orderNo) {
        if (userId == null || clean(externalOrderNo).isEmpty()) return;
        mapper.resolve(userId, clean(externalOrderNo), clean(orderNo), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public PageSlice<RejectedOrderItem> page(String search, String goodsType, OffsetDateTime createdFrom,
                                             int limit, long offset) {
        String keyword = clean(search);
        String type = normalizeGoodsType(goodsType);
        return new PageSlice<>(
            mapper.selectActivePage(keyword, type, createdFrom, limit, offset).stream().map(this::toItem).toList(),
            mapper.countActive(keyword, type, createdFrom)
        );
    }

    @Transactional(readOnly = true)
    public Optional<RejectedOrderItem> find(String orderNo) {
        return Optional.ofNullable(mapper.findActiveByDisplayOrderNo(clean(orderNo))).map(this::toItem);
    }

    @Transactional(readOnly = true)
    public RejectedSummary summary(String search, String goodsType, OffsetDateTime createdFrom) {
        String keyword = clean(search);
        String type = normalizeGoodsType(goodsType);
        return new RejectedSummary(
            mapper.countActive(keyword, type, createdFrom),
            mapper.sumExternalAmount(keyword, type, createdFrom),
            mapper.countMissingExternalAmount(keyword, type, createdFrom)
        );
    }

    private RejectedOrderItem toItem(AgisoRejectedOrderEntity entity) {
        GoodsType type = null;
        try { type = entity.getGoodsType() == null ? null : GoodsType.valueOf(entity.getGoodsType()); }
        catch (IllegalArgumentException ignored) { }
        return new RejectedOrderItem(
            entity.getDisplayOrderNo(), entity.getUserId(), entity.getBuyerAccount(), entity.getProductNo(),
            entity.getGoodsName(), type, "api", entity.getQuantity(),
            unitPrice(entity.getExpectedAmount(), entity.getQuantity()), entity.getExpectedAmount(),
            entity.getExternalMaxAmount(), entity.getExpectedAmount(), "REJECTED",
            entity.getGoodsType(), entity.getRechargeAccount(), parseMap(entity.getRechargeFieldsJson()),
            "阿奇索标准货源订单", entity.getExternalOrderNo(), java.util.List.of(), java.util.List.of(),
            entity.getRejectReason(), entity.getRejectCode(), entity.getRejectReason(), entity.getRejectedAt(),
            entity.getRejectedAt(), entity.getRejectedAt(), entity.getAttemptCount()
        );
    }

    private static String displayOrderNo(Long userId, String externalOrderNo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((userId + ":" + externalOrderNo).getBytes(StandardCharsets.UTF_8));
            return "reject-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String json(Map<String, String> value) {
        try { return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private Map<String, String> parseMap(String value) {
        try { return value == null || value.isBlank() ? Map.of() : OBJECT_MAPPER.readValue(value, STRING_MAP); }
        catch (Exception ex) { return Map.of(); }
    }

    private static String normalizeGoodsType(String value) {
        String normalized = clean(value).toUpperCase();
        return normalized.isEmpty() ? "" : normalized;
    }

    private static BigDecimal decimal(Object value) {
        String text = text(value);
        try {
            if (text.isEmpty()) return null;
            BigDecimal decimal = new BigDecimal(text);
            BigDecimal normalized = decimal.stripTrailingZeros();
            int integerDigits = normalized.precision() - normalized.scale();
            return Math.max(normalized.scale(), 0) > 4 || integerDigits > 14 ? null : decimal;
        }
        catch (NumberFormatException ex) { return null; }
    }

    private static BigDecimal unitPrice(BigDecimal total, Integer quantity) {
        if (total == null || quantity == null || quantity <= 0) return null;
        return total.divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP);
    }

    private static Long longValue(Object value) {
        try { return value == null || text(value).isEmpty() ? null : Long.valueOf(text(value)); }
        catch (NumberFormatException ex) { return null; }
    }

    private static Integer intValue(Object value, int fallback) {
        try { return value == null || text(value).isEmpty() ? fallback : Integer.valueOf(text(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public record RejectedSummary(long total, BigDecimal externalAmount, long missingExternalAmountCount) { }
}
