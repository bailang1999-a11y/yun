package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.FundsLedgerStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 批次7C / 任务C1：{@link UserService} 反向依赖仓储的<b>窄接缝</b>。
 *
 * <p>会员域自己持有 users / userGroups / groupRules / memberCredentials 及其全部 CRUD
 * 与持久化。它还欠仓储的只有<b>另外三个域</b>的东西：
 * <ul>
 *   <li><b>资金域</b>（批次4 的 {@link FundsLedgerStore}）—— 余额加减的条件 UPDATE 与
 *       幂等流水只许住在那个独立事务 bean 里；</li>
 *   <li><b>登录域</b>（口令哈希与令牌失效）—— 批次7C/C2 归 {@code AuthService}；</li>
 *   <li><b>商品域</b>（分类树与文本归一）—— 批次7B 已归 {@link CatalogService}。</li>
 * </ul>
 * 用接口收窄之后，会员域不再能顺手碰订单、卡密、上游 HTTP；批次8 拆 OrderService 时
 * 也能一眼看清「会员还欠仓储什么」。
 *
 * <p>放在 {@code mvp} 包内：沿用批次2/3/6/7B 的判断 —— 接口与新服务留在 mvp，
 * 不为了包隔离去搬迁几十个 DTO。
 */
interface UserGateway {
    // ---- 资金域（批次4，独立 @Transactional bean） ----

    /**
     * 资金是否走 DB 原子路径。
     *
     * <p>每次现取而不在构造时缓存：{@code fundsLedgerStore} 在仓储上是
     * {@code @Autowired(required = false)} 字段注入，测试还会用
     * {@code replaceFundsLedgerStoreForTest} 换实现。
     */
    boolean fundsLedgerEnabled();

    /**
     * 资金流水 store 的<b>当前</b>引用。
     *
     * <p>{@link UserService} 只允许<b>调用</b>它的 {@code creditByAdmin} /
     * {@code debitByAdmin} —— 那里面才有批次4 的条件 UPDATE、受影响行数判定和
     * {@code user_balance_transactions} 幂等流水。会员域不得自己拼余额加减 SQL。
     */
    FundsLedgerStore fundsLedger();

    // ---- 登录域（批次7C/C2 归 AuthService） ----

    /**
     * 管理员重置某会员口令：编码 + 落库 + 失效该会员全部令牌。
     *
     * <p>这四步原先内联在 {@code updateUserCredentials} 里，整块留在仓储不动
     * （口令哈希表 {@code userPasswordHashes} 与令牌表都属登录域）。
     */
    void resetUserPassword(Long userId, String newPassword);

    /** 新口令的强度与两次输入一致性校验。 */
    void validateNewPassword(String password, String confirmPassword);

    /** 失效该会员已签发的全部登录令牌。 */
    void invalidateUserTokens(Long userId);

    /** 注册方式（MOBILE / EMAIL / FREE）归一化。 */
    String normalizeRegistrationType(String value);

    // ---- 商品域（批次7B 已归 CatalogService） ----

    /** 分类及其全部子孙分类 id，用于 CATEGORY 组规则匹配。 */
    Set<Long> categoryTreeIds(Long rootId);

    Optional<CategoryItem> findCategorySnapshot(Long id);

    /** 文本列表归一（去空白、去重、保序）。 */
    List<String> normalizeTextList(List<String> values);
}
