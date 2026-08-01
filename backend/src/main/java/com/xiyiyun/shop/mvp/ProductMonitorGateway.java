package com.xiyiyun.shop.mvp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批次6 / 任务A：{@link ProductMonitorService} 反向依赖仓储的<b>窄接缝</b>。
 *
 * <p>监控服务需要的仓储能力就这几项：读渠道/商品/供应商快照、判定渠道是否可监控、
 * 抓上游快照、把变动写回商品。用接口收窄后，监控逻辑不再能顺手碰到订单、资金、卡密，
 * 也让批次8 拆解仓储时能一眼看清「监控还欠仓储什么」。
 *
 * <p>放在 {@code mvp} 包内：{@link ProductMonitorState} / {@link MonitoredRemoteGoods}
 * 是 package-private 类型，且这里出现的 {@link GoodsItem} / {@link GoodsChannelItem} /
 * {@link SupplierItem} 等 DTO 全部住在 mvp。沿用批次2/3 的判断 ——
 * 接口与新服务留在 mvp，不为了包隔离去搬几十个 DTO。
 */
interface ProductMonitorGateway {
    List<GoodsChannelItem> allGoodsChannelSnapshots();

    Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long channelId);

    Optional<GoodsItem> findGoodsSnapshot(Long goodsId);

    List<GoodsItem> allGoodsSnapshots();

    /** 渠道所属商品开启监控、渠道启用、供应商支持实时同步，三者同时满足。 */
    boolean isProductMonitorChannel(GoodsChannelItem channel);

    boolean isProductMonitorChannel(GoodsChannelItem channel, Map<Long, GoodsItem> goodsById);

    SupplierItem requiredSupplier(Long supplierId);

    /** 抓上游商品快照；供应商不支持或地址是占位符时抛 {@link IllegalStateException}。 */
    MonitoredRemoteGoods monitoredRemoteGoods(GoodsItem current, GoodsChannelItem channel, SupplierItem supplier);

    /** 比对上游与本地，返回应写入的商品，并把人类可读的变更点追加到 {@code changes}。 */
    GoodsItem applyMonitoredRemoteGoods(GoodsItem current, MonitoredRemoteGoods remote, List<String> changes);

    /** 主渠道发现变动时，把商品写回内存与库（持有仓储的 goodsLock）。 */
    void applyMonitoredGoodsUpdate(GoodsItem next);
}
