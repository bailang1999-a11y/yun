package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.SupplierPriceHistoryStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批次6 / 任务A：商品监控（扫描调度 + 上游变动同步 + 监控日志）的唯一出口。
 *
 * <h2>修掉的缺陷：扫描计划重启即丢</h2>
 * 原实现把每个渠道的 {@code nextScanAt} 只放在仓储的内存 Map 里，从不落库。
 * 服务一重启，所有渠道的状态回到「无状态」，{@code ensureProductMonitorState} 会把
 * {@code nextScanAt} 填成 {@code now}，于是<b>全部渠道在启动瞬间同时判定到期</b>，
 * 一起打上游 —— 渠道越多脉冲越猛，足以触发上游限流甚至封号。
 *
 * <p>现在状态经 {@link ConfigService} 落库：启动时 {@link #loadPersistedStates()} 读回，
 * 每轮扫描结束 upsert 一次。重启后各渠道按原来的 {@code nextScanAt} 继续排队，
 * 只有确实已经到期的才会立刻扫。
 *
 * <h2>为什么放在 mvp 包</h2>
 * {@link ProductMonitorState} 与 {@link MonitoredRemoteGoods} 是 package-private，
 * 且监控要用的 {@link GoodsItem} / {@link GoodsChannelItem} / {@link SupplierItem} 等 DTO
 * 全在 mvp。沿用批次2/3 的判断：接口与新服务优先留在 mvp，
 * 不为了教条的包隔离去搬迁几十个 DTO。真正的解耦靠 {@link ProductMonitorGateway} 这道窄接缝。
 */
public class ProductMonitorService {
    private static final Logger log = LoggerFactory.getLogger(ProductMonitorService.class);
    private static final Duration SCAN_INTERVAL = Duration.ofSeconds(180);
    private static final int LOG_RETENTION = 300;
    private static final int LOG_PAGE_LIMIT = 200;

    private final Map<Long, ProductMonitorState> states = new ConcurrentHashMap<>();
    private final Map<Long, ProductMonitorLogItem> logs = new ConcurrentHashMap<>();
    private final AtomicLong logId = new AtomicLong(1);
    private final Object monitorLock = new Object();

    private final ProductMonitorGateway gateway;
    private final OrderEventPublisher realtimeBroadcaster;
    private final ConfigService configService;
    private final SupplierPriceHistoryStore supplierPriceHistoryStore;

    ProductMonitorService(
        ProductMonitorGateway gateway,
        OrderEventPublisher realtimeBroadcaster,
        ConfigService configService
    ) {
        this(gateway, realtimeBroadcaster, configService, null);
    }

    ProductMonitorService(
        ProductMonitorGateway gateway,
        OrderEventPublisher realtimeBroadcaster,
        ConfigService configService,
        SupplierPriceHistoryStore supplierPriceHistoryStore
    ) {
        this.gateway = gateway;
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.configService = configService;
        this.supplierPriceHistoryStore = supplierPriceHistoryStore;
    }

    /**
     * 启动时把已落库的扫描计划读回内存。
     *
     * <p>读不到的渠道保持「无状态」，首次访问时 {@link #ensureState} 会填 {@code now}，
     * 与批次6 之前的首次上线行为一致 —— 修的是<b>重启丢计划</b>，不是改首次上线语义。
     */
    void loadPersistedStates() {
        if (configService == null) {
            return;
        }
        configService.productMonitorStates().forEach(states::put);
    }

    // ------------------------------------------------------------------ 读

    public ProductMonitorOverview overview(Integer page, Integer pageSize) {
        List<ProductMonitorItem> allItems = listItems();
        int normalizedPageSize = Math.min(100, Math.max(1, pageSize == null ? 10 : pageSize));
        int total = allItems.size();
        int normalizedPage = Math.max(1, page == null ? 1 : page);
        int fromIndex = Math.min(total, (normalizedPage - 1) * normalizedPageSize);
        int toIndex = Math.min(total, fromIndex + normalizedPageSize);
        List<ProductMonitorLogItem> logItems = listLogs();
        int activeTotal = (int) allItems.stream().filter(item -> !"FAILED".equals(item.status())).count();
        return new ProductMonitorOverview(
            allItems.subList(fromIndex, toIndex),
            logItems,
            total,
            normalizedPage,
            normalizedPageSize,
            activeTotal,
            logItems.size()
        );
    }

    public List<ProductMonitorLogItem> listLogs() {
        return logs.values().stream()
            .sorted(Comparator.comparing(ProductMonitorLogItem::id).reversed())
            .limit(LOG_PAGE_LIMIT)
            .toList();
    }

    private List<ProductMonitorItem> listItems() {
        OffsetDateTime now = OffsetDateTime.now();
        List<GoodsChannelItem> channelSnapshots = gateway.allGoodsChannelSnapshots();
        Map<Long, GoodsItem> goodsById = gateway.allGoodsSnapshots().stream()
            .collect(Collectors.toMap(GoodsItem::id, item -> item, (first, second) -> second, LinkedHashMap::new));
        List<GoodsChannelItem> monitorChannels = channelSnapshots.stream()
            .filter(channel -> gateway.isProductMonitorChannel(channel, goodsById))
            .toList();
        Set<Long> primaryChannelIds = monitorChannels.stream()
            .collect(Collectors.groupingBy(
                GoodsChannelItem::goodsId,
                Collectors.minBy(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            ))
            .values()
            .stream()
            .flatMap(Optional::stream)
            .map(GoodsChannelItem::id)
            .collect(Collectors.toSet());
        return monitorChannels.stream()
            .sorted(Comparator.comparing(GoodsChannelItem::supplierName).thenComparing(GoodsChannelItem::supplierGoodsId))
            .map(channel -> toItem(channel, ensureState(channel.id(), now), goodsById.get(channel.goodsId()), primaryChannelIds.contains(channel.id())))
            .toList();
    }

    /** 到期渠道 = 未在扫描中，且 nextScanAt 已过（重启后靠落库的 nextScanAt 判定，不再全部立刻到期）。 */
    public List<Long> dueChannelIds(OffsetDateTime now) {
        return gateway.allGoodsChannelSnapshots().stream()
            .filter(gateway::isProductMonitorChannel)
            .filter(channel -> {
                ProductMonitorState state = ensureState(channel.id(), now);
                return !state.scanning() && (state.nextScanAt() == null || !state.nextScanAt().isAfter(now));
            })
            .map(GoodsChannelItem::id)
            .sorted()
            .toList();
    }

    // ------------------------------------------------------------------ 扫描

    public List<ProductMonitorScanResult> scanAll(boolean manual) {
        return gateway.allGoodsChannelSnapshots().stream()
            .filter(gateway::isProductMonitorChannel)
            .sorted(Comparator.comparing(GoodsChannelItem::id))
            .map(channel -> scanChannel(channel.id(), manual))
            .filter(Objects::nonNull)
            .toList();
    }

    public ProductMonitorScanResult scanChannel(Long channelId, boolean manual) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        GoodsChannelItem channel;
        GoodsItem current;
        synchronized (monitorLock) {
            channel = gateway.findGoodsChannelSnapshot(channelId).orElse(null);
            if (channel == null || !gateway.isProductMonitorChannel(channel)) {
                return null;
            }
            states.put(channelId, ensureState(channelId, startedAt).start(startedAt));
            current = gateway.findGoodsSnapshot(channel.goodsId()).orElse(null);
        }

        SupplierItem supplier;
        List<String> changes = new ArrayList<>();
        String result = "NO_CHANGE";
        String message = "本轮扫描无变动";
        boolean changed = false;

        try {
            if (current == null) {
                throw new IllegalStateException("本地商品不存在");
            }
            supplier = gateway.requiredSupplier(channel.supplierId());
            if (!"ENABLED".equals(supplier.status())) {
                throw new IllegalStateException("供应商已停用");
            }
            if (!"ENABLED".equals(channel.status())) {
                throw new IllegalStateException("渠道已停用");
            }

            boolean primaryChannel = isPrimaryChannel(channel);
            MonitoredRemoteGoods remote = gateway.monitoredRemoteGoods(current, channel, supplier);
            recordSupplierPrice(channel, remote);
            GoodsItem next = gateway.applyMonitoredRemoteGoods(current, remote, changes);
            changed = !changes.isEmpty();
            if (changed && primaryChannel) {
                gateway.applyMonitoredGoodsUpdate(next);
                result = "CHANGED";
                message = "主渠道发现上游变动，已同步本地商品";
            } else if (changed) {
                result = "CHANGED";
                message = "非主渠道发现上游变动，仅记录日志，不覆盖本地商品";
            }
        } catch (RuntimeException ex) {
            result = "FAILED";
            message = ex.getMessage() == null ? "扫描失败" : ex.getMessage();
        }

        OffsetDateTime finishedAt = OffsetDateTime.now();
        OffsetDateTime nextScanAt = finishedAt.plus(SCAN_INTERVAL);
        ProductMonitorState state;
        ProductMonitorLogItem log;
        synchronized (monitorLock) {
            state = ensureState(channelId, finishedAt).finish(finishedAt, nextScanAt, result, message, changed);
            states.put(channelId, state);

            log = new ProductMonitorLogItem(
                logId.getAndIncrement(),
                channel.id(),
                channel.goodsId(),
                gateway.findGoodsSnapshot(channel.goodsId()).map(GoodsItem::goodsName).orElse("-"),
                channel.supplierId(),
                channel.supplierName(),
                channel.supplierGoodsId(),
                result,
                message,
                List.copyOf(changes),
                finishedAt,
                nextScanAt
            );
            logs.put(log.id(), log);
            trimLogs();
        }
        // 落库放在锁外：一次 upsert 的 IO 不该阻塞其它渠道的扫描判定。
        persistState(state);
        realtimeBroadcaster.publishProductMonitorLog(log);
        return new ProductMonitorScanResult(toItem(channel, state), log);
    }

    private void recordSupplierPrice(GoodsChannelItem channel, MonitoredRemoteGoods remote) {
        if (supplierPriceHistoryStore == null || remote == null) return;
        BigDecimal unitPrice = remote.integration() == null || remote.integration().supplierPrice() == null
            ? remote.price()
            : remote.integration().supplierPrice();
        try {
            supplierPriceHistoryStore.record(channel, unitPrice, OffsetDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("supplier price history unavailable for channel {}, monitoring continues: {}",
                channel.id(), ex.toString());
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 渠道被删除时清掉其状态（含落库的那份，否则重启后会读回幽灵状态）。 */
    void forgetChannel(Long channelId) {
        if (channelId == null) {
            return;
        }
        states.remove(channelId);
        if (configService != null) {
            configService.forgetProductMonitorState(channelId);
        }
    }

    /** 商品被删除时连带清掉该商品所有渠道的状态与日志。 */
    void forgetChannelWithLogs(Long channelId) {
        forgetChannel(channelId);
        logs.entrySet().removeIf(entry -> Objects.equals(entry.getValue().channelId(), channelId));
    }

    // -------------------------------------------------------------------- 内部

    private void persistState(ProductMonitorState state) {
        if (configService == null) {
            return;
        }
        configService.saveProductMonitorState(state);
    }

    private ProductMonitorState ensureState(Long channelId, OffsetDateTime now) {
        return states.computeIfAbsent(channelId, id -> new ProductMonitorState(
            id,
            null,
            now,
            "WAITING",
            "等待首次扫描",
            0,
            0,
            false
        ));
    }

    private ProductMonitorItem toItem(GoodsChannelItem channel, ProductMonitorState state) {
        GoodsItem item = gateway.findGoodsSnapshot(channel.goodsId()).orElse(null);
        return toItem(channel, state, item, isPrimaryChannel(channel));
    }

    private ProductMonitorItem toItem(GoodsChannelItem channel, ProductMonitorState state, GoodsItem item, boolean primaryChannel) {
        return new ProductMonitorItem(
            channel.id(),
            channel.goodsId(),
            item == null ? "-" : item.goodsName(),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            primaryChannel,
            state.lastResult(),
            state.lastScanAt(),
            state.nextScanAt(),
            state.lastResult(),
            state.lastMessage(),
            state.scanCount(),
            state.changeCount()
        );
    }

    private boolean isPrimaryChannel(GoodsChannelItem channel) {
        return gateway.allGoodsChannelSnapshots().stream()
            .filter(gateway::isProductMonitorChannel)
            .filter(item -> Objects.equals(item.goodsId(), channel.goodsId()))
            .min(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .map(item -> Objects.equals(item.id(), channel.id()))
            .orElse(false);
    }

    private void trimLogs() {
        int overflow = logs.size() - LOG_RETENTION;
        if (overflow <= 0) {
            return;
        }
        logs.values().stream()
            .sorted(Comparator.comparing(ProductMonitorLogItem::id))
            .limit(overflow)
            .map(ProductMonitorLogItem::id)
            .toList()
            .forEach(logs::remove);
    }
}
