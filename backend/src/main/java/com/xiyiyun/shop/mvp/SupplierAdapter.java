package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.OrderStatus;

/**
 * 上游供应商适配器。
 *
 * <p>取代原先复制了 6 遍的「按 platformType 判断是哪家 → 调对应私有方法」分发链。
 * 每家一个实现类，由 {@link SupplierAdapterRegistry} 按 platformType 查找。</p>
 *
 * <h3>能力不是所有家都有</h3>
 * <p>能力用 {@code supportsXxx()} 查询方法表达，默认实现在不支持时抛
 * {@link UnsupportedOperationException}，而不是返回空结果假装成功：</p>
 * <ul>
 *   <li>福禄、蜂助手<b>不提供上游商品列表</b>（原 {@code syncRemoteGoods} 里的
 *       {@code platformLabelForManualSupplier} 分支即为此），故
 *       {@link #supportsRemoteGoodsSync()} 返回 false，
 *       {@link #fetchRemoteGoods} 保持默认抛不支持。</li>
 * </ul>
 */
public interface SupplierAdapter {

    /** 本适配器负责的平台。 */
    SupplierPlatform platform();

    default boolean matches(String platformType) {
        return platform().matches(platformType);
    }

    default boolean matches(SupplierItem item) {
        return item != null && matches(item.platformType());
    }

    /** 面向运营的中文平台名。 */
    default String displayName() {
        return platform().displayName();
    }

    // ---------------------------------------------------------------- 能力查询

    /**
     * 是否支持拉取上游商品列表。
     * <p>福禄 / 蜂助手为 false —— 上游不提供该接口，需在商品对接里手动填写上游商品编码。</p>
     */
    boolean supportsRemoteGoodsSync();

    /**
     * 是否属于「API 供应商」。
     * <p>严格对应原 {@code isApiSupplierPlatform}：仅卡速售与咔咔云。
     * 该标记在原代码里被用于商品详情/库存快照等只有这两家才有的能力判定，
     * 语义上等价于「上游提供单品查询接口」，故保留为独立能力位而不是与 remoteGoodsSync 合并。</p>
     */
    boolean supportsSingleGoodsQuery();

    /**
     * 商品同步页「手动填写上游编码」提示语。
     * <p>对应原 {@code platformLabelForManualSupplier(item) + "不提供上游商品列表…"}。</p>
     */
    default String manualGoodsMappingHint() {
        return displayName() + "不提供上游商品列表，请在商品对接里手动填写上游商品编码";
    }

    /**
     * 货源对接页不支持远程拉取商品时的提示语。
     * <p>原代码里只有福禄有专属文案（"福禄新平台不支持获取上游商品，请手动填写 product_id 创建商品对接"），
     * 其余走通用文案，故默认返回通用文案。</p>
     */
    default String sourceConnectUnsupportedHint() {
        return "当前供应商不支持远程拉取商品，请手动创建或绑定上游商品编码";
    }

    /** 是否支持远程刷新余额。7 家全部支持。 */
    default boolean supportsBalanceRefresh() {
        return true;
    }

    /** 是否支持真实下单。7 家全部支持。 */
    default boolean supportsOrderSubmit() {
        return true;
    }

    /**
     * 拉取上游商品时单页上限。
     * <p>对应原 {@code fetchIntegratedRemoteGoods} 里逐家写死的 {@code Math.min(limit, 100)}：
     * 卡速售不夹取，其余 4 家夹到 100。改为适配器声明，避免分发处再按家名分支。</p>
     */
    default int maxRemoteGoodsPageSize() {
        return 100;
    }

    /**
     * 更新供应商时 userId 是否要回落到 appId/appKey。
     * <p>对应原第 2081 行那串 6 个 {@code isXxxPlatform} 的或串（实为 7 家，
     * 因 isApiSupplierPlatform 含 2 家）。7 家全部为 true。</p>
     */
    default boolean usesIdentityFallback() {
        return true;
    }

    // ---------------------------------------------------------------- 上游操作

    /** 测试连通性。对应原 {@code testXxxConnection}。 */
    SupplierItem testConnection(SupplierCallContext context);

    /** 刷新余额。对应原 {@code refreshXxxBalance}。 */
    SupplierItem refreshBalance(SupplierCallContext context);

    /**
     * 提交采购订单。对应原 {@code submitXxxProcurementOrder}。
     *
     * @throws SupplierBusinessException  上游明确拒单 —— 调用方可置 FAILED
     * @throws SupplierTransportException 超时/网络/5xx/无法解析 —— 调用方必须转 PROCURING（缺陷 A4）
     */
    UpstreamSubmitResult submitOrder(
        SupplierCallContext context,
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price
    );

    /**
     * 查询上游订单状态。对应原订单查询分发链里的 {@code fetchXxxOrderStatus} + 状态映射。
     *
     * @param fallback 上游未给出终态时沿用的本地状态
     */
    UpstreamOrderSnapshot fetchOrderStatus(SupplierCallContext context, OrderItem order, OrderStatus fallback);

    /**
     * 拉取上游商品列表。对应原 {@code fetchXxxGoods}。
     * <p>不支持该能力的实现（福禄/蜂助手）保持默认抛出，不提供空实现。</p>
     */
    default RemoteGoodsSyncResult fetchRemoteGoods(
        SupplierCallContext context,
        Long categoryId,
        String keyword,
        int page,
        int limit
    ) {
        throw new UnsupportedOperationException(
            displayName() + "不提供上游商品列表，请在商品对接里手动填写上游商品编码"
        );
    }
}
