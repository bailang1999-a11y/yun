package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

public record ProcurementPrice(BigDecimal unitCost, BigDecimal totalCost) {
    public static ProcurementPrice resolve(GoodsItem goods, GoodsChannelItem channel, Integer quantity) {
        if (goods == null || channel == null) {
            throw new IllegalStateException("采购商品或渠道不存在");
        }
        int normalizedQuantity = quantity == null ? 1 : quantity;
        if (normalizedQuantity <= 0) {
            throw new IllegalStateException("采购数量必须大于 0");
        }
        BigDecimal unitCost = integrations(goods).stream()
            .filter(item -> !Boolean.FALSE.equals(item.enabled()))
            .filter(item -> Objects.equals(item.supplierId(), channel.supplierId()))
            .filter(item -> sameText(item.supplierGoodsId(), channel.supplierGoodsId()))
            .map(GoodsIntegrationItem::supplierPrice)
            .filter(ProcurementPrice::usable)
            .findFirst()
            .orElse(goods.price());
        if (!usable(unitCost)) {
            throw new IllegalStateException("渠道采购价和本地商品售价均不可用");
        }
        return new ProcurementPrice(
            unitCost,
            unitCost.multiply(BigDecimal.valueOf(normalizedQuantity))
        );
    }

    private static List<GoodsIntegrationItem> integrations(GoodsItem goods) {
        return goods.integrations() == null ? List.of() : goods.integrations();
    }

    private static boolean sameText(String left, String right) {
        return StringUtils.hasText(left)
            && StringUtils.hasText(right)
            && left.trim().equals(right.trim());
    }

    private static boolean usable(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
