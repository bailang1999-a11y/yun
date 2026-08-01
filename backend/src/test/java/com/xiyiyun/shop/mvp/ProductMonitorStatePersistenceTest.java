package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigTableStore;
import com.xiyiyun.shop.persistence.entity.ProductMonitorStateRecordEntity;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 批次6 / 任务A 的缺陷回归：商品监控扫描计划必须能跨重启存活。
 *
 * <p>修复前 {@code productMonitorStates} 是纯内存 Map，重启后每个渠道的 {@code nextScanAt}
 * 归零，{@code ensureState} 把它填成 {@code now}，于是<b>所有渠道在启动瞬间同时到期</b>，
 * 一起打上游。本测试用「同一份 KV 存储 + 新建服务实例」模拟重启，
 * 断言计划没丢、且不是所有渠道都立刻重扫。
 */
class ProductMonitorStatePersistenceTest {

    /** 用真实的 KV 语义（一个可变 Map）替掉 DB，重启前后共用同一份，等价于同一张表。 */
    private static ConfigPersistenceStore kvStore(Map<String, String> backing) {
        ConfigPersistenceStore store = mock(ConfigPersistenceStore.class);
        when(store.systemSettings()).thenAnswer(invocation -> new LinkedHashMap<>(backing));
        doAnswer(invocation -> {
            backing.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(store).saveRuntimeSetting(anyString(), anyString());
        // 批次7：监控状态从 system_settings 迁到 product_monitor_states。
        // 这里同样用一个可变 Map 当那张表，重启前后共用，语义与原来的 KV 桩一致。
        // 注意 monitorTable() 必须在 when() 之外先建好：在 when(...) 参数里造 mock
        // 会被 Mockito 判成 UnfinishedStubbing。
        ConfigTableStore tables = monitorTable(new LinkedHashMap<>());
        when(store.configTables()).thenReturn(tables);
        return store;
    }

    /** product_monitor_states 的行级桩：upsert 按 channelId 覆盖，delete 是真删。 */
    private static ConfigTableStore monitorTable(Map<Long, ProductMonitorStateRecordEntity> rows) {
        ConfigTableStore tables = mock(ConfigTableStore.class);
        when(tables.listProductMonitorStates()).thenAnswer(invocation -> List.copyOf(rows.values()));
        doAnswer(invocation -> {
            ProductMonitorStateRecordEntity entity = invocation.getArgument(0);
            rows.put(entity.getChannelId(), entity);
            return null;
        }).when(tables).saveProductMonitorState(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            rows.remove(invocation.<Long>getArgument(0));
            return null;
        }).when(tables).deleteProductMonitorState(org.mockito.ArgumentMatchers.any());
        return tables;
    }

    private static List<Long> persistedChannelIds(ConfigPersistenceStore store) {
        return store.configTables().listProductMonitorStates().stream()
            .map(ProductMonitorStateRecordEntity::getChannelId)
            .toList();
    }

    private static ProductMonitorService newService(ConfigPersistenceStore store, ProductMonitorGateway gateway) {
        ConfigService configService = new ConfigService(
            store,
            new AuditService(null),
            systemSetting(),
            value -> value,
            groupId -> groupId
        );
        ProductMonitorService service = new ProductMonitorService(
            gateway,
            mock(OrderEventPublisher.class),
            configService
        );
        service.loadPersistedStates();
        return service;
    }

    @Test
    void scanScheduleSurvivesRestartSoChannelsDoNotAllRescanAtOnce() {
        Map<String, String> backing = new LinkedHashMap<>();
        ConfigPersistenceStore store = kvStore(backing);
        StubGateway gateway = new StubGateway();

        ProductMonitorService before = newService(store, gateway);
        // 首次上线：两个渠道都没有状态，都应判定到期（这是原本就正确的行为，不动它）。
        assertThat(before.dueChannelIds(OffsetDateTime.now())).containsExactly(30001L, 30002L);

        // 扫一个渠道，它的 nextScanAt 被推到 now + 180s 并落库。
        assertThat(before.scanChannel(30001L, true)).isNotNull();
        OffsetDateTime scannedAt = OffsetDateTime.now();
        assertThat(before.dueChannelIds(scannedAt)).containsExactly(30002L);
        // 批次7：落库位置由 system_settings['product.monitor.state.30001'] 换成
        // product_monitor_states 的一行。断言语义不变：这个渠道的计划确实落盘了。
        assertThat(persistedChannelIds(store)).contains(30001L);
        assertThat(backing).doesNotContainKey("product.monitor.state.30001");

        // 模拟重启：进程内状态全丢，只剩存储里那份。
        ProductMonitorService afterRestart = newService(store, gateway);

        // 关键断言：已扫过的渠道不会因为重启就立刻重扫。
        assertThat(afterRestart.dueChannelIds(scannedAt))
            .as("重启后已排好计划的渠道不应立刻重扫")
            .containsExactly(30002L);

        // nextScanAt 本身也要原值存活（不是被抹成 now）。
        ProductMonitorItem restored = afterRestart.overview(1, 20).items().stream()
            .filter(item -> item.channelId().equals(30001L))
            .findFirst()
            .orElseThrow();
        assertThat(restored.nextScanAt())
            .as("nextScanAt 必须跨重启存活")
            .isAfter(scannedAt);
        assertThat(restored.scanCount()).isEqualTo(1);

        // 计划到期后仍然会正常重扫，不是被永久冻结。
        assertThat(afterRestart.dueChannelIds(restored.nextScanAt().plusSeconds(1)))
            .containsExactly(30001L, 30002L);
    }

    @Test
    void deletedChannelDoesNotComeBackAfterRestart() {
        Map<String, String> backing = new LinkedHashMap<>();
        ConfigPersistenceStore store = kvStore(backing);
        StubGateway gateway = new StubGateway();

        ProductMonitorService before = newService(store, gateway);
        before.scanChannel(30001L, true);
        assertThat(persistedChannelIds(store)).contains(30001L);
        before.forgetChannel(30001L);

        // 批次7 新增：批次6 只能写空串当墓碑（那批次不许加迁移、mapper 也没有 DELETE），
        // 迁到独立表后这里是真 DELETE，表里不该再留残行。
        assertThat(persistedChannelIds(store)).doesNotContain(30001L);

        // 渠道从目录里移除后，重启不该读回幽灵状态。
        gateway.channels.removeIf(channel -> channel.id().equals(30001L));
        ProductMonitorService afterRestart = newService(store, gateway);
        assertThat(afterRestart.dueChannelIds(OffsetDateTime.now())).containsExactly(30002L);
    }

    /** 最小可用的监控网关桩：两个渠道、一个商品，扫描一律「无变动」。 */
    private static final class StubGateway implements ProductMonitorGateway {
        private final List<GoodsChannelItem> channels = new java.util.ArrayList<>(List.of(
            channel(30001L, 1),
            channel(30002L, 2)
        ));

        private static GoodsChannelItem channel(Long id, int priority) {
            return new GoodsChannelItem(id, 10001L, 20001L, "供应商A", "SG-" + id, priority, 30, "ENABLED", OffsetDateTime.now());
        }

        @Override
        public List<GoodsChannelItem> allGoodsChannelSnapshots() {
            return List.copyOf(channels);
        }

        @Override
        public Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long channelId) {
            return channels.stream().filter(item -> item.id().equals(channelId)).findFirst();
        }

        @Override
        public Optional<GoodsItem> findGoodsSnapshot(Long goodsId) {
            return Optional.empty();
        }

        @Override
        public List<GoodsItem> allGoodsSnapshots() {
            return List.of();
        }

        @Override
        public boolean isProductMonitorChannel(GoodsChannelItem channel) {
            return channel != null && channels.contains(channel);
        }

        @Override
        public boolean isProductMonitorChannel(GoodsChannelItem channel, Map<Long, GoodsItem> goodsById) {
            return isProductMonitorChannel(channel);
        }

        @Override
        public SupplierItem requiredSupplier(Long supplierId) {
            throw new IllegalStateException("桩：不打真实上游");
        }

        @Override
        public MonitoredRemoteGoods monitoredRemoteGoods(GoodsItem current, GoodsChannelItem channel, SupplierItem supplier) {
            throw new IllegalStateException("桩：不打真实上游");
        }

        @Override
        public GoodsItem applyMonitoredRemoteGoods(GoodsItem current, MonitoredRemoteGoods remote, List<String> changes) {
            return current;
        }

        @Override
        public void applyMonitoredGoodsUpdate(GoodsItem next) {
        }
    }

    private static SystemSettingItem systemSetting() {
        return new SystemSettingItem(
            "喜易云", "", "", "", "", "", "", "MOCK", true, "TENCENT", false, 30,
            true, false, true, "MOBILE", 1L, Map.of()
        );
    }
}
