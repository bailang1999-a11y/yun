package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.persistence.SupplierPriceHistoryStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class ProductMonitorPriceHistoryTest {
    @Test
    void successfulScanRecordsTheUpstreamSupplierUnitPrice() {
        ProductMonitorGateway gateway = mock(ProductMonitorGateway.class);
        OrderEventPublisher broadcaster = mock(OrderEventPublisher.class);
        SupplierPriceHistoryStore priceHistoryStore = mock(SupplierPriceHistoryStore.class);
        GoodsChannelItem channel = new GoodsChannelItem(
            30001L, 10001L, 20001L, "供应商A", "SG-1", 1, 30, "ENABLED", OffsetDateTime.now()
        );
        GoodsItem goods = mock(GoodsItem.class);
        SupplierItem supplier = mock(SupplierItem.class);
        GoodsIntegrationItem integration = mock(GoodsIntegrationItem.class);
        BigDecimal supplierPrice = new BigDecimal("8.7600");
        MonitoredRemoteGoods remote = new MonitoredRemoteGoods(
            "测试商品", new BigDecimal("9.9900"), 100, "ENABLED", integration
        );

        when(goods.id()).thenReturn(10001L);
        when(supplier.status()).thenReturn("ENABLED");
        when(integration.supplierPrice()).thenReturn(supplierPrice);
        when(gateway.allGoodsChannelSnapshots()).thenReturn(List.of(channel));
        when(gateway.findGoodsChannelSnapshot(30001L)).thenReturn(Optional.of(channel));
        when(gateway.findGoodsSnapshot(10001L)).thenReturn(Optional.of(goods));
        when(gateway.isProductMonitorChannel(channel)).thenReturn(true);
        when(gateway.requiredSupplier(20001L)).thenReturn(supplier);
        when(gateway.monitoredRemoteGoods(goods, channel, supplier)).thenReturn(remote);
        when(gateway.applyMonitoredRemoteGoods(
            ArgumentMatchers.eq(goods),
            ArgumentMatchers.eq(remote),
            ArgumentMatchers.anyList()
        )).thenReturn(goods);

        ProductMonitorService service = new ProductMonitorService(
            gateway, broadcaster, null, priceHistoryStore
        );

        service.scanChannel(30001L, true);

        verify(priceHistoryStore).record(
            ArgumentMatchers.eq(channel),
            ArgumentMatchers.eq(supplierPrice),
            ArgumentMatchers.any(OffsetDateTime.class)
        );
    }

    @Test
    void priceHistoryFailureDoesNotFailTheProductMonitorScan() {
        ProductMonitorGateway gateway = mock(ProductMonitorGateway.class);
        OrderEventPublisher broadcaster = mock(OrderEventPublisher.class);
        SupplierPriceHistoryStore priceHistoryStore = mock(SupplierPriceHistoryStore.class);
        GoodsChannelItem channel = new GoodsChannelItem(
            30002L, 10002L, 20002L, "供应商B", "SG-2", 1, 30, "ENABLED", OffsetDateTime.now()
        );
        GoodsItem goods = mock(GoodsItem.class);
        SupplierItem supplier = mock(SupplierItem.class);
        MonitoredRemoteGoods remote = new MonitoredRemoteGoods(
            "测试商品", new BigDecimal("6.6600"), 100, "ENABLED", null
        );

        when(goods.id()).thenReturn(10002L);
        when(supplier.status()).thenReturn("ENABLED");
        when(gateway.allGoodsChannelSnapshots()).thenReturn(List.of(channel));
        when(gateway.findGoodsChannelSnapshot(30002L)).thenReturn(Optional.of(channel));
        when(gateway.findGoodsSnapshot(10002L)).thenReturn(Optional.of(goods));
        when(gateway.isProductMonitorChannel(channel)).thenReturn(true);
        when(gateway.requiredSupplier(20002L)).thenReturn(supplier);
        when(gateway.monitoredRemoteGoods(goods, channel, supplier)).thenReturn(remote);
        when(gateway.applyMonitoredRemoteGoods(
            ArgumentMatchers.eq(goods),
            ArgumentMatchers.eq(remote),
            ArgumentMatchers.anyList()
        )).thenReturn(goods);
        doThrow(new IllegalStateException("history table unavailable"))
            .when(priceHistoryStore).record(ArgumentMatchers.eq(channel), any(), any());
        ProductMonitorService service = new ProductMonitorService(
            gateway, broadcaster, null, priceHistoryStore
        );

        ProductMonitorScanResult result = service.scanChannel(30002L, true);

        assertThat(result.log().result()).isEqualTo("NO_CHANGE");
        verify(gateway).applyMonitoredRemoteGoods(
            ArgumentMatchers.eq(goods),
            ArgumentMatchers.eq(remote),
            ArgumentMatchers.anyList()
        );
    }
}
