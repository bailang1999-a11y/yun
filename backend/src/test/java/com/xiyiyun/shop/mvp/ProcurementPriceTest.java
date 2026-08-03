package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xiyiyun.shop.GoodsType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementPriceTest {
    private static final GoodsChannelItem CHANNEL = new GoodsChannelItem(
        30001L, 10001L, 20001L, "测试供应商", "UP-100", 10, 30, "ENABLED", OffsetDateTime.now()
    );

    @Test
    void prefersTheSelectedChannelsUpstreamPriceAndCalculatesTotalCost() {
        GoodsItem goods = goods(
            new BigDecimal("104.1600"),
            List.of(integration(20001L, "UP-100", new BigDecimal("99.2000"), true))
        );

        ProcurementPrice price = ProcurementPrice.resolve(goods, CHANNEL, 2);

        assertThat(price.unitCost()).isEqualByComparingTo("99.2000");
        assertThat(price.totalCost()).isEqualByComparingTo("198.4000");
    }

    @Test
    void fallsBackToTheLocalBaseSalePriceInsteadOfTheOrderPayment() {
        GoodsItem goods = goods(
            new BigDecimal("88.5000"),
            List.of(integration(20002L, "OTHER", new BigDecimal("77.0000"), true))
        );

        ProcurementPrice price = ProcurementPrice.resolve(goods, CHANNEL, 1);

        assertThat(price.unitCost()).isEqualByComparingTo("88.5000");
        assertThat(price.totalCost()).isEqualByComparingTo("88.5000");
    }

    @Test
    void ignoresDisabledOrNonPositiveChannelPrices() {
        GoodsItem goods = goods(
            new BigDecimal("66.0000"),
            List.of(
                integration(20001L, "UP-100", new BigDecimal("55.0000"), false),
                integration(20001L, "UP-100", BigDecimal.ZERO, true)
            )
        );

        assertThat(ProcurementPrice.resolve(goods, CHANNEL, null).unitCost())
            .isEqualByComparingTo("66.0000");
    }

    @Test
    void rejectsAnOrderOnlyWhenBothChannelAndLocalPricesAreUnavailable() {
        GoodsItem goods = goods(null, List.of());

        assertThatThrownBy(() -> ProcurementPrice.resolve(goods, CHANNEL, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("均不可用");
    }

    private static GoodsIntegrationItem integration(
        Long supplierId,
        String supplierGoodsId,
        BigDecimal price,
        boolean enabled
    ) {
        return new GoodsIntegrationItem(
            "integration", supplierId, "测试供应商", "test", supplierGoodsId,
            "上游商品", price, "ON_SALE", 999, "上游商品", "2026-08-03T00:00:00Z", enabled
        );
    }

    private static GoodsItem goods(BigDecimal price, List<GoodsIntegrationItem> integrations) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GoodsItem(
            10001L, 1L, "分类", "测试商品", "测试商品", "", "",
            List.of(), "", "", false, "", "", List.of(), List.of(), integrations,
            true, true, GoodsType.DIRECT, "ALL", price, price, 10, true, List.of("sg"),
            "template", "DYNAMIC", BigDecimal.ONE, BigDecimal.ZERO, 999, 0, "ON_SALE",
            List.of(), now, now, List.of(), List.of(), null
        );
    }
}
