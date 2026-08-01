package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * 上游商品条目 → {@link RemoteGoodsItem} 的映射端口，由 {@link InMemoryShopRepository} 实现。
 *
 * <p><b>为什么保留这个回调端口</b>：单条商品的映射需要读本地状态才能算出
 * 「是否已对接」「本地商品名」「限价」等字段（依赖 sourceConnectedChannel / findGoodsSnapshot
 * 以及本地价格模板），这些是仓储职责而不是上游协议职责。本批次让适配器负责
 * <b>协议层</b>（请求构造、签名、分页、响应码校验、列表/分类节点定位），
 * 把<b>单条映射</b>回调给仓储，从而保证商品字段映射逻辑<b>零改动</b>。</p>
 *
 * <p>接口定义在 mvp 包内，与批次2 的判断标准一致：若为此新建 supplier 子包，
 * 端口需引用 RemoteGoodsItem 等 mvp DTO，而 mvp 又需引用 registry，形成双向依赖。</p>
 */
public interface SupplierGoodsMappingPort {

    /** 卡速售商品条目映射（原 {@code remoteGoodsItem}）。 */
    RemoteGoodsItem kasushouItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    );

    /** 咔咔云商品条目映射（原 {@code kakayunRemoteGoodsItem}）。 */
    RemoteGoodsItem kakayunItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    );

    /** 橙券商品条目映射（原 {@code chengquanRemoteGoodsItem}）。 */
    RemoteGoodsItem chengquanItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    );

    /** 梵尘商品条目映射（原 {@code fanchenRemoteGoodsItem}）。 */
    RemoteGoodsItem fanchenItem(Long supplierId, JsonNode node);

    /** 京兆商品条目映射（原 {@code jingzhaoRemoteGoodsItem}）。 */
    RemoteGoodsItem jingzhaoItem(Long supplierId, JsonNode node);

    /** 卡速售分类节点解析（原 {@code kasushouCategories}）。 */
    List<Map<String, Object>> kasushouCategories(JsonNode data);

    /** 咔咔云分类节点解析（原 {@code kakayunCategories}）。 */
    List<Map<String, Object>> kakayunCategories(JsonNode data);

    /** 分类 id→name 索引（原 {@code remoteCategoryNames}）。 */
    Map<String, String> categoryNames(List<Map<String, Object>> categories);

    /** 京兆商品类型中文名（原 {@code jingzhaoGoodsTypeLabel}）。 */
    String jingzhaoGoodsTypeLabel(String type);

    /** 关键字过滤（原各 fetchXxxGoods 内联的 normalize().contains() 判断）。 */
    boolean matchesKeyword(RemoteGoodsItem item, String keyword);

    /** 把 JSON 数组转成 {@code List<Map<String,Object>>}（原 OBJECT_MAPPER.convertValue + LIST_MAP_TYPE）。 */
    List<Map<String, Object>> toMapList(JsonNode node);
}
