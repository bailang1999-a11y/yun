package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.ChannelAttemptItem;
import com.xiyiyun.shop.mvp.GoodsChannelItem;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.SupplierPriceTrendItem;
import com.xiyiyun.shop.mvp.SupplierPriceTrendPoint;
import com.xiyiyun.shop.persistence.entity.SupplierPriceHistoryEntity;
import com.xiyiyun.shop.persistence.mapper.SupplierPriceHistoryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierPriceHistoryStore {
    private final SupplierPriceHistoryMapper mapper;

    public SupplierPriceHistoryStore(SupplierPriceHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void record(GoodsChannelItem channel, BigDecimal unitPrice, OffsetDateTime observedAt) {
        if (channel == null || channel.id() == null || channel.goodsId() == null || channel.supplierId() == null
            || unitPrice == null || unitPrice.signum() < 0) {
            return;
        }
        if (mapper.lockChannel(channel.id()) == null) return;
        BigDecimal normalizedPrice = unitPrice.setScale(4, RoundingMode.HALF_UP);
        SupplierPriceHistoryEntity latest = mapper.selectLatestByChannelId(channel.id());
        if (latest != null && latest.getUnitPrice() != null
            && latest.getUnitPrice().compareTo(normalizedPrice) == 0) {
            return;
        }

        BigDecimal previousPrice = latest == null ? null : latest.getUnitPrice();
        BigDecimal changeAmount = previousPrice == null
            ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            : normalizedPrice.subtract(previousPrice).setScale(4, RoundingMode.HALF_UP);
        SupplierPriceHistoryEntity entity = new SupplierPriceHistoryEntity();
        entity.setGoodsId(channel.goodsId());
        entity.setChannelId(channel.id());
        entity.setSupplierId(channel.supplierId());
        entity.setSupplierName(channel.supplierName() == null ? "" : channel.supplierName());
        entity.setSupplierGoodsId(channel.supplierGoodsId() == null ? "" : channel.supplierGoodsId());
        entity.setUnitPrice(normalizedPrice);
        entity.setPreviousUnitPrice(previousPrice);
        entity.setChangeAmount(changeAmount);
        entity.setDirection(previousPrice == null ? "INITIAL" : changeAmount.signum() > 0 ? "UP" : "DOWN");
        entity.setObservedAt(observedAt == null ? OffsetDateTime.now() : observedAt);
        mapper.insertSnapshot(entity);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public List<OrderItem> attachRecentTrends(List<OrderItem> orders) {
        if (orders == null || orders.isEmpty()) return orders == null ? List.of() : orders;

        Map<String, Long> channelsByOrderNo = new LinkedHashMap<>();
        Set<Long> missingGoodsIds = orders.stream()
            .filter(order -> {
                Long channelId = latestAttemptChannelId(order);
                if (channelId != null) channelsByOrderNo.put(order.orderNo(), channelId);
                return channelId == null;
            })
            .map(OrderItem::goodsId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        Map<Long, Long> primaryChannels = missingGoodsIds.isEmpty()
            ? Map.of()
            : mapper.selectPrimaryChannels(missingGoodsIds.stream().toList()).stream()
                .collect(Collectors.toMap(
                    GoodsPrimaryChannelProjection::getGoodsId,
                    GoodsPrimaryChannelProjection::getChannelId
                ));
        orders.stream()
            .filter(order -> !channelsByOrderNo.containsKey(order.orderNo()))
            .filter(order -> order.goodsId() != null && primaryChannels.containsKey(order.goodsId()))
            .forEach(order -> channelsByOrderNo.put(order.orderNo(), primaryChannels.get(order.goodsId())));

        List<Long> channelIds = channelsByOrderNo.values().stream().distinct().toList();
        if (channelIds.isEmpty()) return orders;
        Map<Long, List<SupplierPriceHistoryEntity>> histories = mapper.selectRecentByChannelIds(channelIds).stream()
            .collect(Collectors.groupingBy(
                SupplierPriceHistoryEntity::getChannelId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        Map<Long, SupplierPriceTrendItem> trends = histories.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> toTrend(entry.getValue())));
        return orders.stream()
            .map(order -> order.withSupplierPriceTrend(trends.get(channelsByOrderNo.get(order.orderNo()))))
            .toList();
    }

    private Long latestAttemptChannelId(OrderItem order) {
        List<ChannelAttemptItem> attempts = order == null ? null : order.channelAttempts();
        if (attempts == null) return null;
        for (int index = attempts.size() - 1; index >= 0; index--) {
            ChannelAttemptItem attempt = attempts.get(index);
            if (attempt != null && attempt.channelId() != null) return attempt.channelId();
        }
        return null;
    }

    private SupplierPriceTrendItem toTrend(List<SupplierPriceHistoryEntity> history) {
        SupplierPriceHistoryEntity latest = history.getLast();
        List<SupplierPriceTrendPoint> points = history.stream()
            .map(item -> new SupplierPriceTrendPoint(
                item.getUnitPrice(), item.getChangeAmount(), item.getDirection(), item.getObservedAt()
            ))
            .toList();
        return new SupplierPriceTrendItem(
            latest.getChannelId(),
            latest.getSupplierId(),
            latest.getSupplierName(),
            latest.getSupplierGoodsId(),
            latest.getUnitPrice(),
            latest.getDirection(),
            points
        );
    }
}
