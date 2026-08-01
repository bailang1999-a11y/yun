package com.xiyiyun.shop.mvp;

import java.util.List;
import java.util.Optional;

/**
 * 批次7B / 任务B：{@link CatalogService} 反向依赖仓储的<b>窄接缝</b>。
 *
 * <p>商品域自己持有 goods / categories / cardKinds / goodsChannels / rechargeFields
 * 及其全部 CRUD 与持久化。它还欠仓储的只有<b>另外三个域</b>的东西：
 * <ul>
 *   <li><b>卡密域</b>（真实可售卡密计数、删商品时清卡）—— 卡密属于交付，留在仓储；</li>
 *   <li><b>会员域</b>（会员组与组规则，用于按组过滤可见商品与算组内价）；</li>
 *   <li><b>供应商域</b>（供应商快照与上游远程商品快照）—— 上游 HTTP 适配留在仓储。</li>
 * </ul>
 * 用接口收窄之后，商品域不再能顺手碰订单、资金、令牌；批次8 拆 OrderService 时
 * 也能一眼看清「商品还欠仓储什么」。
 *
 * <p>放在 {@code mvp} 包内：沿用批次2/3/6 的判断 —— 接口与新服务留在 mvp，
 * 不为了包隔离去搬迁几十个 DTO。
 */
interface CatalogGateway {
    // ---- 卡密域（交付侧，留在仓储） ----

    /** 商品维度真实可售卡密数；持久化模式下走 FundsLedgerStore 的定点统计。 */
    int availableCardCount(Long goodsId);

    /** 卡类维度真实可售卡密数。 */
    int availableCardKindCardCount(Long cardKindId);

    /** 卡类维度卡密总数（含已用）。 */
    int cardKindTotalCount(Long cardKindId);

    /** 卡类维度已使用卡密数。 */
    int cardKindUsedCount(Long cardKindId);

    /** 删除商品时同步清掉内存中的卡密。 */
    void removeCardsForGoods(Long goodsId);

    /** 删除商品时同步清掉库里的卡密。 */
    void deletePersistentCardsByGoods(Long goodsId);

    /** 资金 / 库存是否走 DB 原子路径（决定库存刷新用定点 UPDATE 还是整行 upsert）。 */
    boolean fundsLedgerEnabled();

    /** 持久化模式下把 CARD 商品标称库存对齐到真实卡密数（定点 UPDATE）。 */
    void syncCardGoodsStock(Long goodsId, Long cardKindId);

    // ---- 会员域 ----

    Optional<UserGroupItem> findUserGroupSnapshot(Long groupId);

    List<GroupRuleItem> rulesForGroup(Long groupId);

    boolean allowedByGroupRules(GoodsItem item, List<GroupRuleItem> rules);

    // ---- 供应商域 ----

    SupplierItem requiredSupplier(Long supplierId);

    Optional<SupplierItem> supplierSnapshot(Long supplierId);

    /** 已缓存的上游同步结果中，该供应商商品对应的集成快照。 */
    Optional<GoodsIntegrationItem> cachedRemoteIntegration(SupplierItem supplier, String supplierGoodsId);

    // ---- 商品监控（批次6） ----

    void forgetMonitorChannel(Long channelId);

    void forgetMonitorChannelWithLogs(Long channelId);
}
