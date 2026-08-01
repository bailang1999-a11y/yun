package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.persistence.CatalogPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 批次7B / 任务B：商品目录域（商品 / 分类 / 卡类 / 商品渠道 / 充值字段 / 价格模板）的唯一出口。
 *
 * <h2>为什么单独一层</h2>
 * 这五组实体原先和订单、资金、令牌、上游 HTTP 一起挤在一万行的
 * {@code InMemoryShopRepository} 里。它们的内存 Map、id 序列、DB 镜像、
 * 读降级、字段归一化、以及「上游集成 ↔ 商品渠道」的对账，全都散落在仓储各处。
 * 本类把这些收口：仓储只调有语义的方法，不再直接碰 {@code goods} / {@code categories} /
 * {@code cardKinds} / {@code goodsChannels} / {@code rechargeFields} 这几张表。
 *
 * <h2>顺手收回来的实体表 CRUD</h2>
 * 批次6 把 {@code system_settings} 的 KV 流量收进了 {@link ConfigService}，但
 * {@link ConfigPersistenceStore} 上还挂着<b>卡类 / 充值字段 / 供应商 / 商品渠道</b>四组
 * <b>实体表</b>的 CRUD —— 那是实体持久化不是配置，批次6 刻意没搬。本批次把这四组的
 * 读写口收进本类（供应商只收「持久化」那一段，上游 HTTP 适配仍在仓储），
 * 于是仓储不再持有 {@link ConfigPersistenceStore}。
 *
 * <h2>锁为什么由外部传进来</h2>
 * {@code goodsLock} / {@code categoryLock} 不是商品域独占的：仓储的下单、退款、
 * 货源克隆等订单侧代码也在同一把 {@code goodsLock} 上同步。所以两把监视器仍在仓储里声明，
 * 由构造器把<b>同一个对象引用</b>传进来。两边的 {@code synchronized} 块因此仍然互斥，
 * 与重构前逐字等价 —— 这是纯结构重构，不碰任何并发语义。
 *
 * <h2>为什么放在 mvp 包</h2>
 * {@link CategoryItem} / {@link GoodsItem} / {@link CardKindItem} / {@link GoodsChannelItem}
 * 等 DTO 全在 mvp，其中若干是 package-private。沿用批次2/3/6 的判断：
 * 接口与新服务优先留在 mvp，不为了教条的包隔离去搬迁几十个 DTO。
 * 真正的解耦靠 {@link CatalogGateway} 这道窄接缝。
 */
public class CatalogService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern PRICE_LIMIT_PATTERN = Pattern.compile("(?:限价|限制售价|限定价格|控价)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*(?:元|块|rmb|RMB|¥)?)");
    private static final Set<String> SALES_TERMINAL_PLATFORMS = Set.of("all", "h5", "web", "pc", "api");
    private static final Set<String> LEGACY_SYSTEM_GOODS_TAGS = Set.of("new", "api-source");
    private static final String DEFAULT_PRICE_LIMIT_NOTICE = "当前会员组暂未开放限价商品购买权限，请联系平台客服处理。";

    private final Map<Long, CategoryItem> categories = new ConcurrentHashMap<>();
    private final Map<Long, CardKindItem> cardKinds = new ConcurrentHashMap<>();
    private final Map<Long, GoodsItem> goods = new ConcurrentHashMap<>();
    private final Map<Long, GoodsChannelItem> goodsChannels = new ConcurrentHashMap<>();
    private final Map<Long, RechargeFieldItem> rechargeFields = new ConcurrentHashMap<>();
    private final List<PriceTemplateItem> priceTemplates = new ArrayList<>();

    private final AtomicLong goodsId = new AtomicLong(10003);
    private final AtomicLong categoryId = new AtomicLong(40000);
    private final AtomicLong cardKindId = new AtomicLong(1);
    private final AtomicLong channelId = new AtomicLong(30002);
    private final AtomicLong rechargeFieldId = new AtomicLong(7);

    /** 与仓储共用的监视器（见类注释「锁为什么由外部传进来」）。 */
    private final Object goodsLock;
    private final Object categoryLock;
    private final Object priceTemplateLock = new Object();

    private final CatalogGateway gateway;
    private final AuditService auditService;
    private final ConfigService configService;
    private final CatalogPersistenceStore catalogPersistenceStore;
    private final ConfigPersistenceStore configPersistenceStore;

    CatalogService(
        CatalogGateway gateway,
        AuditService auditService,
        ConfigService configService,
        CatalogPersistenceStore catalogPersistenceStore,
        ConfigPersistenceStore configPersistenceStore,
        Object goodsLock,
        Object categoryLock
    ) {
        this.gateway = gateway;
        this.auditService = auditService;
        this.configService = configService;
        this.catalogPersistenceStore = catalogPersistenceStore;
        this.configPersistenceStore = configPersistenceStore;
        this.goodsLock = goodsLock;
        this.categoryLock = categoryLock;
    }

    // ------------------------------------------------------------------ 内存态访问（供仓储的其它域读取）

    Map<Long, GoodsItem> goodsMap() {
        return goods;
    }

    Map<Long, CardKindItem> cardKindsMap() {
        return cardKinds;
    }

    Map<Long, GoodsChannelItem> goodsChannelsMap() {
        return goodsChannels;
    }

    Map<Long, RechargeFieldItem> rechargeFieldsMap() {
        return rechargeFields;
    }

    boolean catalogPersistenceEnabled() {
        return catalogPersistenceStore != null;
    }

    boolean configPersistenceEnabled() {
        return configPersistenceStore != null;
    }

    // ------------------------------------------------------------------ 转发到 AuditService / 小工具（与仓储同名同语义）

    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        auditService.appendOperation(action, resourceType, resourceId, remark);
    }

    private String persistenceErrorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private Long allocateNextCandidateId(AtomicLong sequence, long maxExistingId) {
        long id = Math.max(sequence.get(), maxExistingId + 1);
        sequence.set(id + 1);
        return id;
    }

    private Long allocateIncrementingId(AtomicLong sequence, long maxExistingId) {
        long id = Math.max(sequence.get() + 1, maxExistingId + 1);
        sequence.set(id);
        return id;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String requiredText(String value, String fallback) {
        String result = defaultText(value, fallback).trim();
        if (!StringUtils.hasText(result)) {
            throw new IllegalArgumentException("field label is required");
        }
        return result;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String defaultText(Object value, String fallback) {
        return value == null ? fallback : defaultText(String.valueOf(value), fallback);
    }

    private String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        if (StringUtils.hasText(second)) {
            return second;
        }
        return fallback;
    }

    private int availableCardCount(Long targetGoodsId) {
        return gateway.availableCardCount(targetGoodsId);
    }

    private int availableCardKindCardCount(Long targetCardKindId) {
        return gateway.availableCardKindCardCount(targetCardKindId);
    }

    private boolean fundsLedgerEnabled() {
        return gateway.fundsLedgerEnabled();
    }

    private Optional<UserGroupItem> findUserGroupSnapshot(Long id) {
        return gateway.findUserGroupSnapshot(id);
    }

    private List<GroupRuleItem> rulesForGroup(Long groupId) {
        return gateway.rulesForGroup(groupId);
    }

    private boolean allowedByGroupRules(GoodsItem item, List<GroupRuleItem> rules) {
        return gateway.allowedByGroupRules(item, rules);
    }

    private SupplierItem requiredSupplier(Long id) {
        return gateway.requiredSupplier(id);
    }

    /**
     * 与仓储同名同实现的小工具（仓储侧另有 2 处调用点仍需它）。
     *
     * <p>沿用批次6 {@link ConfigService} 的做法：几行的纯函数就地复制一份，
     * 不为了消重再拉一个静态工具类出来。
     */
    private String integrationKey(GoodsIntegrationItem item) {
        return defaultText(item.supplierId() == null ? "" : String.valueOf(item.supplierId()), item.platformCode())
            + ":" + defaultText(item.supplierGoodsId(), "");
    }

    // ------------------------------------------------------------------ 以下为从 InMemoryShopRepository 整块搬入的商品域实现

    public List<CategoryItem> listCategories() {
        Optional<List<CategoryItem>> persistent = persistentCategories();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        List<CategoryItem> snapshot = categories.values().stream()
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .toList();
        Map<Long, CategoryItem> byId = snapshot.stream()
            .collect(java.util.stream.Collectors.toMap(CategoryItem::id, item -> item, (left, right) -> left));
        Set<Long> parentIds = snapshot.stream()
            .map(CategoryItem::parentId)
            .filter(parentId -> parentId != null && parentId != 0L)
            .collect(java.util.stream.Collectors.toSet());
        return snapshot.stream()
            .map(item -> enrichCategory(item, byId, parentIds))
            .toList();
    }

    public List<CardKindItem> listCardKinds() {
        Optional<List<CardKindItem>> persistent = persistentCardKinds();
        if (persistent.isPresent()) {
            return persistent.get().stream()
                .map(this::enrichCardKind)
                .toList();
        }
        return cardKinds.values().stream()
            .sorted(Comparator.comparing(CardKindItem::id))
            .map(this::enrichCardKind)
            .toList();
    }

    public synchronized CardKindItem createCardKind(CreateCardKindRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("card kind name is required");
        }
        String type = normalizeCardKindType(request.type());
        if (request.cost() != null && request.cost().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("card kind cost cannot be negative");
        }
        Long id = allocateNextCandidateId(cardKindId, maxCardKindId());
        CardKindItem item = new CardKindItem(
            id,
            request.name().trim(),
            type,
            request.cost() == null ? BigDecimal.ZERO : request.cost()
        );
        cardKinds.put(id, item);
        persistCardKind(item);
        return enrichCardKind(item);
    }

    public CategoryItem createCategory(CreateCategoryRequest request) {
        synchronized (categoryLock) {
            if (request == null || !StringUtils.hasText(request.name())) {
                throw new IllegalArgumentException("category name is required");
            }
            Long parentId = request.parentId() == null ? 0L : request.parentId();
            if (parentId != 0L && findCategorySnapshot(parentId).isEmpty()) {
                throw new IllegalArgumentException("parent category not found");
            }
            int level = categoryLevel(parentId);
            if (level >= 5) {
                throw new IllegalStateException("category depth cannot exceed 5");
            }
            Long id = allocateIncrementingId(categoryId, maxCategoryId());
            boolean enabled = categoryEnabled(request.enabled(), request.status());
            CategoryItem item = new CategoryItem(
                id,
                request.name().trim(),
                defaultText(request.nickname(), ""),
                parentId,
                normalizeCategoryIcon(request.icon()),
                normalizeCategoryIcon(request.iconUrl()),
                normalizeCategoryIcon(request.customIconUrl()),
                request.sort() == null ? (int) (id % 1000) : request.sort(),
                enabled,
                categoryStatus(enabled),
                categoryLevel(parentId) + 1,
                false
            );
            categories.put(id, item);
            persistCategorySnapshot(item);
            return enrichCategory(item);
        }
    }

    public CategoryItem updateCategory(Long id, UpdateCategoryRequest request) {
        synchronized (categoryLock) {
            CategoryItem current = findCategorySnapshot(id).orElse(null);
            if (current == null) {
                throw new IllegalArgumentException("category not found");
            }
            if (request == null) {
                throw new IllegalArgumentException("category update request is required");
            }
            Long parentId = request.parentId() == null ? current.parentId() : request.parentId();
            validateCategoryParent(id, parentId);
            String name = request.name() == null ? current.name() : request.name().trim();
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException("category name is required");
            }
            int newLevel = categoryLevel(parentId) + 1;
            if (newLevel + categorySubtreeHeight(id) - 1 > 5) {
                throw new IllegalStateException("category depth cannot exceed 5");
            }
            boolean enabled = request.enabled() == null && !StringUtils.hasText(request.status())
                ? current.enabled() == null || current.enabled()
                : categoryEnabled(request.enabled(), request.status());
            CategoryItem next = new CategoryItem(
                current.id(),
                name,
                request.nickname() == null ? current.nickname() : defaultText(request.nickname(), ""),
                parentId,
                request.icon() == null ? current.icon() : normalizeCategoryIcon(request.icon()),
                request.iconUrl() == null ? current.iconUrl() : normalizeCategoryIcon(request.iconUrl()),
                request.customIconUrl() == null ? current.customIconUrl() : normalizeCategoryIcon(request.customIconUrl()),
                request.sort() == null ? current.sort() : request.sort(),
                enabled,
                categoryStatus(enabled),
                newLevel,
                hasChildCategory(current.id())
            );
            categories.put(id, next);
            persistCategorySnapshot(next);
            return enrichCategory(next);
        }
    }

    public CategoryItem updateCategoryStatus(Long id, boolean enabled) {
        synchronized (categoryLock) {
            CategoryItem item = findCategorySnapshot(id).orElse(null);
            if (item == null) {
                throw new IllegalArgumentException("category not found");
            }
            CategoryItem next = new CategoryItem(
                item.id(),
                item.name(),
                item.nickname(),
                item.parentId(),
                item.icon(),
                item.iconUrl(),
                item.customIconUrl(),
                item.sort(),
                enabled,
                categoryStatus(enabled),
                categoryLevel(item.parentId()) + 1,
                hasChildCategory(item.id())
            );
            categories.put(id, next);
            persistCategorySnapshot(next);
            return enrichCategory(next);
        }
    }

    public void deleteCategory(Long id) {
        synchronized (categoryLock) {
            CategoryItem item = findCategorySnapshot(id).orElse(null);
            if (item == null) {
                throw new IllegalArgumentException("category not found");
            }
            if (hasChildCategory(id)) {
                throw new IllegalStateException("category has child categories and cannot be deleted");
            }
            if (allGoodsSnapshots().stream().anyMatch(goodsItem -> Objects.equals(goodsItem.categoryId(), id))) {
                throw new IllegalStateException("category is referenced by goods and cannot be deleted");
            }
            categories.remove(id);
            deletePersistentCategory(id);
        }
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, boolean admin) {
        return listGoods(categoryId, search, platform, null, admin);
    }

    public List<GoodsListItem> listPublicGoods(Long categoryId, String search, String platform, Long userGroupId) {
        return pagePublicGoods(categoryId, search, platform, userGroupId, 1, Integer.MAX_VALUE).items();
    }

    public PageResult<GoodsListItem> pagePublicGoods(Long categoryId, String search, String platform, Long userGroupId, int page, int pageSize) {
        List<GoodsItem> source = persistentGoods()
            .map(ArrayList::new)
            .orElseGet(() -> new ArrayList<>(goods.values()));
        List<GoodsItem> filtered = filterGoodsBase(source, categoryId, search, platform, userGroupId, false)
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int from = Math.min((safePage - 1) * safePageSize, filtered.size());
        int to = Math.min(from + safePageSize, filtered.size());
        List<GoodsChannelItem> channelSnapshots = allGoodsChannelSnapshots();
        List<GoodsListItem> items = filtered.subList(from, to).stream()
            .map(item -> withEffectivePrice(item, userGroupId))
            .map(item -> withChannelIntegrations(item, channelSnapshots))
            .map(GoodsListItem::from)
            .toList();
        return new PageResult<>(items, filtered.size(), safePage, safePageSize);
    }

    public List<GoodsListItem> listAdminGoods(Long categoryId, String search, String platform) {
        Optional<List<GoodsItem>> persistent = persistentGoods();
        List<GoodsItem> items = persistent
            .map(ArrayList::new)
            .orElseGet(() -> new ArrayList<>(goods.values()));
        List<GoodsChannelItem> channelSnapshots = allGoodsChannelSnapshots();
        String keyword = normalize(search);
        String normalizedPlatform = normalize(platform);
        Set<Long> categoryScope = categoryId == null ? Set.of() : categoryTreeIds(categoryId);

        return items.stream()
            .filter(item -> categoryId == null || categoryScope.contains(item.categoryId()))
            .filter(item -> !StringUtils.hasText(normalizedPlatform) || goodsAllowsPlatform(item, normalizedPlatform))
            .filter(item -> !StringUtils.hasText(keyword) || containsKeyword(item, keyword))
            .map(item -> persistent.isPresent() ? item : refreshStock(item))
            .map(item -> withChannelIntegrations(item, channelSnapshots))
            .sorted(Comparator.comparing(GoodsItem::id))
            .map(GoodsListItem::from)
            .toList();
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, Long userGroupId, boolean admin) {
        Optional<List<GoodsItem>> persistent = persistentGoods();
        if (persistent.isPresent()) {
            List<GoodsItem> items = persistent.get();
            ensureGoodsChannelsForIntegrations(items);
            return filterGoods(items, categoryId, search, platform, userGroupId, admin, false);
        }
        List<GoodsItem> items = new ArrayList<>(goods.values());
        ensureGoodsChannelsForIntegrations(items);
        return filterGoods(items, categoryId, search, platform, userGroupId, admin, true);
    }

    private List<GoodsItem> filterGoods(
        List<GoodsItem> source,
        Long categoryId,
        String search,
        String platform,
        Long userGroupId,
        boolean admin,
        boolean refresh
    ) {
        String keyword = normalize(search);
        String normalizedPlatform = normalize(platform);
        Set<Long> categoryScope = categoryId == null ? Set.of() : categoryTreeIds(categoryId);
        UserGroupItem activeGroup = admin ? null : findUserGroupSnapshot(userGroupId == null ? 1L : userGroupId).orElse(null);
        List<GroupRuleItem> activeRules = activeGroup == null ? List.of() : rulesForGroup(activeGroup.id());
        List<GoodsChannelItem> channelSnapshots = allGoodsChannelSnapshots();
        return filterGoodsBase(source, categoryId, search, platform, userGroupId, admin)
            .map(item -> refresh ? refreshStock(item) : item)
            .map(item -> admin ? item : withEffectivePrice(item, userGroupId))
            .map(item -> withChannelIntegrations(item, channelSnapshots))
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    private java.util.stream.Stream<GoodsItem> filterGoodsBase(
        List<GoodsItem> source,
        Long categoryId,
        String search,
        String platform,
        Long userGroupId,
        boolean admin
    ) {
        String keyword = normalize(search);
        String normalizedPlatform = normalize(platform);
        Set<Long> categoryScope = categoryId == null ? Set.of() : categoryTreeIds(categoryId);
        UserGroupItem activeGroup = admin ? null : findUserGroupSnapshot(userGroupId == null ? 1L : userGroupId).orElse(null);
        List<GroupRuleItem> activeRules = activeGroup == null ? List.of() : rulesForGroup(activeGroup.id());
        return source.stream()
            .filter(item -> admin || "ON_SALE".equals(item.status()))
            .filter(item -> categoryId == null || categoryScope.contains(item.categoryId()))
            .filter(item -> admin || !StringUtils.hasText(normalizedPlatform) || goodsAllowsPlatform(item, normalizedPlatform))
            .filter(item -> admin || allowedByGroupRules(item, activeRules))
            .filter(item -> !StringUtils.hasText(keyword) || containsKeyword(item, keyword));
    }

    public Optional<GoodsItem> findGoods(Long id) {
        return findGoodsSnapshot(id).map(this::refreshStock);
    }

    public Optional<GoodsItem> findGoods(Long id, Long userGroupId, boolean admin) {
        return findGoods(id, userGroupId, admin, "h5");
    }

    public Optional<GoodsItem> findGoods(Long id, Long userGroupId, boolean admin, String platform) {
        return findGoods(id)
            .filter(item -> admin || "ON_SALE".equals(item.status()))
            .filter(item -> admin || goodsAllowsPlatform(item, platform))
            .filter(item -> admin || allowedByGroupRules(item, rulesForGroup(userGroupId == null ? 1L : userGroupId)))
            .map(item -> admin ? item : withEffectivePrice(item, userGroupId));
    }

    public List<PriceTemplateItem> listPriceTemplates() {
        synchronized (priceTemplateLock) {
            ensurePriceTemplatesReadyLocked();
            return priceTemplates.stream()
                .map(this::sanitizePriceTemplate)
                .toList();
        }
    }

    public List<PriceTemplateItem> savePriceTemplates(List<PriceTemplateItem> request) {
        synchronized (priceTemplateLock) {
            priceTemplates.clear();
            if (request != null) {
                request.stream()
                    .map(this::sanitizePriceTemplate)
                    .filter(item -> StringUtils.hasText(item.id()) && StringUtils.hasText(item.name()))
                    .forEach(priceTemplates::add);
            }
            if (priceTemplates.isEmpty()) {
                priceTemplates.add(defaultPriceTemplate());
            }
            List<PriceTemplateItem> snapshot = sanitizedPriceTemplatesSnapshotLocked();
            persistPriceTemplates(snapshot);
            return snapshot;
        }
    }

    public List<RechargeFieldItem> listRechargeFields(Boolean enabled) {
        Optional<List<RechargeFieldItem>> persistent = persistentRechargeFields();
        if (persistent.isPresent()) {
            return persistent.get().stream()
                .filter(item -> enabled == null || Objects.equals(item.enabled(), enabled))
                .sorted(Comparator.comparing(RechargeFieldItem::sort).thenComparing(RechargeFieldItem::id))
                .toList();
        }
        return rechargeFields.values().stream()
            .filter(item -> enabled == null || Objects.equals(item.enabled(), enabled))
            .sorted(Comparator.comparing(RechargeFieldItem::sort).thenComparing(RechargeFieldItem::id))
            .toList();
    }

    public synchronized RechargeFieldItem createRechargeField(RechargeFieldRequest request) {
        String code = normalizeRechargeFieldCode(request == null ? "" : request.code());
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("字段标识不能为空");
        }
        if (!isValidRechargeFieldCode(code)) {
            throw new IllegalArgumentException("字段标识需以英文字母开头，仅支持小写英文、数字、下划线");
        }
        if (rechargeFieldCodeExists(code, null)) {
            throw new IllegalStateException("字段标识已存在");
        }

        Long id = allocateIncrementingId(rechargeFieldId, maxRechargeFieldId());
        OffsetDateTime now = OffsetDateTime.now();
        RechargeFieldItem item = new RechargeFieldItem(
            id,
            code,
            requiredText(request == null ? "" : request.label(), "充值字段"),
            defaultText(request == null ? "" : request.placeholder(), ""),
            defaultText(request == null ? "" : request.helpText(), ""),
            normalizeRechargeFieldInputType(request == null ? "" : request.inputType()),
            request != null && Boolean.TRUE.equals(request.required()),
            request == null || request.sort() == null ? (int) (id * 10) : request.sort(),
            request == null || request.enabled() == null || request.enabled(),
            now,
            now
        );
        rechargeFields.put(id, item);
        persistRechargeField(item);
        return item;
    }

    public synchronized RechargeFieldItem updateRechargeField(Long id, RechargeFieldRequest request) {
        RechargeFieldItem current = findRechargeFieldSnapshot(id).orElse(null);
        if (current == null) {
            throw new IllegalArgumentException("recharge field not found");
        }

        String code = normalizeRechargeFieldCode(firstText(request == null ? "" : request.code(), current.code(), current.code()));
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("字段标识不能为空");
        }
        if (!isValidRechargeFieldCode(code)) {
            throw new IllegalArgumentException("字段标识需以英文字母开头，仅支持小写英文、数字、下划线");
        }
        if (rechargeFieldCodeExists(code, id)) {
            throw new IllegalStateException("字段标识已存在");
        }

        RechargeFieldItem next = new RechargeFieldItem(
            current.id(),
            code,
            requiredText(request == null ? "" : request.label(), current.label()),
            defaultText(request == null ? "" : request.placeholder(), current.placeholder()),
            defaultText(request == null ? "" : request.helpText(), current.helpText()),
            normalizeRechargeFieldInputType(defaultText(request == null ? "" : request.inputType(), current.inputType())),
            request == null || request.required() == null ? current.required() : request.required(),
            request == null || request.sort() == null ? current.sort() : request.sort(),
            request == null || request.enabled() == null ? current.enabled() : request.enabled(),
            current.createdAt(),
            OffsetDateTime.now()
        );
        rechargeFields.put(id, next);
        persistRechargeField(next);
        return next;
    }

    public synchronized RechargeFieldItem updateRechargeFieldEnabled(Long id, boolean enabled) {
        RechargeFieldItem current = findRechargeFieldSnapshot(id).orElse(null);
        if (current == null) {
            throw new IllegalArgumentException("recharge field not found");
        }
        RechargeFieldItem next = new RechargeFieldItem(
            current.id(),
            current.code(),
            current.label(),
            current.placeholder(),
            current.helpText(),
            current.inputType(),
            current.required(),
            current.sort(),
            enabled,
            current.createdAt(),
            OffsetDateTime.now()
        );
        rechargeFields.put(id, next);
        persistRechargeField(next);
        return next;
    }

    public synchronized void deleteRechargeField(Long id) {
        if (findRechargeFieldSnapshot(id).isEmpty()) {
            throw new IllegalArgumentException("recharge field not found");
        }
        rechargeFields.remove(id);
        deletePersistentRechargeField(id);
    }

    public List<GoodsChannelItem> listGoodsChannels(Long targetGoodsId) {
        if (findGoodsSnapshot(targetGoodsId).isEmpty()) {
            throw new IllegalArgumentException("goods not found");
        }
        Optional<List<GoodsChannelItem>> persistent = persistentGoodsChannels();
        if (persistent.isPresent()) {
            return persistent.get().stream()
                .filter(item -> Objects.equals(item.goodsId(), targetGoodsId))
                .sorted(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
                .toList();
        }
        return goodsChannels.values().stream()
            .filter(item -> Objects.equals(item.goodsId(), targetGoodsId))
            .sorted(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .toList();
    }

    public GoodsChannelItem createGoodsChannel(Long targetGoodsId, CreateGoodsChannelRequest request) {
        synchronized (goodsLock) {
            GoodsItem targetGoods = findGoodsSnapshot(targetGoodsId).orElse(null);
            if (targetGoods == null) {
                throw new IllegalArgumentException("goods not found");
            }
            if (targetGoods.type() != GoodsType.DIRECT) {
                throw new IllegalStateException("only direct goods can bind supplier channels");
            }
            if (request == null || request.supplierId() == null) {
                throw new IllegalArgumentException("supplierId is required");
            }
            SupplierItem supplier = requiredSupplier(request.supplierId());
            if (!StringUtils.hasText(request.supplierGoodsId())) {
                throw new IllegalArgumentException("supplierGoodsId is required");
            }
            Long id = allocateIncrementingId(channelId, maxGoodsChannelId());
            GoodsChannelItem item = new GoodsChannelItem(
                id,
                targetGoodsId,
                supplier.id(),
                supplier.name(),
                request.supplierGoodsId().trim(),
                request.priority() == null ? 10 : request.priority(),
                request.timeoutSeconds() == null ? 30 : request.timeoutSeconds(),
                defaultText(request.status(), "ENABLED"),
                OffsetDateTime.now()
            );
            goodsChannels.put(id, item);
            persistGoodsChannel(item);
            return item;
        }
    }

    public void deleteGoodsChannel(Long targetGoodsId, Long targetChannelId) {
        synchronized (goodsLock) {
            GoodsChannelItem item = findGoodsChannelSnapshot(targetChannelId).orElse(null);
            if (item == null || !Objects.equals(item.goodsId(), targetGoodsId)) {
                throw new IllegalArgumentException("channel not found");
            }
            goodsChannels.remove(targetChannelId);
            gateway.forgetMonitorChannel(targetChannelId);
            deletePersistentGoodsChannel(targetChannelId);
        }
    }

    public void deleteGoods(Long targetGoodsId) {
        synchronized (goodsLock) {
            GoodsItem item = findGoodsSnapshot(targetGoodsId).orElse(null);
            if (item == null) {
                throw new IllegalArgumentException("goods not found");
            }
            goods.remove(targetGoodsId);
            gateway.removeCardsForGoods(targetGoodsId);
            List<Long> channelIds = allGoodsChannelSnapshots().stream()
                .filter(channel -> Objects.equals(channel.goodsId(), targetGoodsId))
                .map(GoodsChannelItem::id)
                .filter(Objects::nonNull)
                .toList();
            channelIds.forEach(channelId -> {
                goodsChannels.remove(channelId);
                gateway.forgetMonitorChannelWithLogs(channelId);
            });
            deletePersistentGoods(targetGoodsId);
            deletePersistentGoodsChannelsByGoods(targetGoodsId);
            gateway.deletePersistentCardsByGoods(targetGoodsId);
            appendOperation("GOODS_DELETE", "GOODS", String.valueOf(targetGoodsId), item.goodsName());
        }
    }

    /**
     * 批次6 / 任务A：主渠道发现上游变动后把商品写回本地。
     *
     * <p>从原 {@code scanProductMonitorChannel} 里整块搬出来，是
     * {@link ProductMonitorGateway} 唯一的写入口。锁与持久化语义原样保留。
     */
    void applyMonitoredGoodsUpdate(GoodsItem next) {
        if (next == null) {
            return;
        }
        synchronized (goodsLock) {
            goods.put(next.id(), next);
            persistGoodsSnapshot(next);
        }
    }

    public synchronized int repairBenefitDurationsFromTitles() {
        List<GoodsItem> items = allGoodsSnapshots();
        int changedCount = 0;
        for (GoodsItem item : items) {
            List<String> nextDurations = inferredBenefitDurations(item.goodsName());
            if (nextDurations.isEmpty() || Objects.equals(nextDurations, item.benefitDurations())) {
                continue;
            }
            GoodsItem next = new GoodsItem(
                item.id(),
                item.categoryId(),
                item.categoryName(),
                item.goodsName(),
                item.name(),
                item.subTitle(),
                item.description(),
                nextDurations,
                item.benefitType(),
                item.benefitBrand(),
                item.priceLimited(),
                item.priceLimitText(),
                item.coverUrl(),
                item.detailImages(),
                item.detailBlocks(),
                item.integrations(),
                item.pollingEnabled(),
                item.monitoringEnabled(),
                item.type(),
                item.platform(),
                item.price(),
                item.originalPrice(),
                item.maxBuy(),
                item.requireRechargeAccount(),
                item.accountTypes(),
                item.priceTemplateId(),
                item.priceMode(),
                item.priceCoefficient(),
                item.priceFixedAdd(),
                item.stock(),
                item.sales(),
                item.status(),
                item.tags(),
                item.createdAt(),
                OffsetDateTime.now(),
                item.availablePlatforms(),
                item.forbiddenPlatforms(),
                item.cardKindId()
            );
            goods.put(next.id(), next);
            persistGoodsSnapshot(next);
            changedCount++;
        }
        return changedCount;
    }

    BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    int normalizedPriority(Integer value) {
        return value == null ? 10 : Math.max(1, value);
    }

    int normalizedChannelTimeout(Integer value) {
        return value == null ? 30 : Math.max(5, value);
    }

    public GoodsItem createGoods(CreateGoodsRequest request) {
        synchronized (goodsLock) {
            Long id = allocateIncrementingId(goodsId, maxGoodsId());
            Long categoryId = request.categoryId() == null ? 1L : request.categoryId();
            CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
            OffsetDateTime now = OffsetDateTime.now();
            GoodsType type = request.type() == null ? GoodsType.CARD : request.type();
            Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), null);
            List<String> accountTypes = type == GoodsType.CARD
                ? List.of()
                : validateEnabledRechargeFieldCodes(request.accountTypes());
            String nextGoodsName = firstText(request.goodsName(), request.name(), "新商品 " + id);
            String nextName = firstText(request.name(), request.goodsName(), "新商品 " + id);
            String nextPriceLimitText = normalizedPriceLimitText(request.priceLimitText(), true, nextGoodsName, nextName);
            GoodsItem item = new GoodsItem(
                id,
                categoryId,
                category == null ? "未分类" : category.name(),
                nextGoodsName,
                nextName,
                defaultText(request.subTitle(), "MVP 内存商品"),
                defaultText(request.description(), "这是一个用于前端联调的内存商品。"),
                normalizeTextList(request.benefitDurations()),
                defaultText(request.benefitType(), ""),
                defaultText(request.benefitBrand(), ""),
                StringUtils.hasText(nextPriceLimitText),
                nextPriceLimitText,
                defaultText(request.coverUrl(), "https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=800&q=80"),
                normalizeImages(request.detailImages()),
                normalizeDetailBlocks(request.detailBlocks()),
                normalizeIntegrations(request.integrations()),
                Boolean.TRUE.equals(request.pollingEnabled()),
                request.monitoringEnabled() == null || request.monitoringEnabled(),
                type,
                defaultText(request.platform(), "GENERAL"),
                request.price() == null ? BigDecimal.valueOf(9.90) : request.price(),
                request.originalPrice() == null ? BigDecimal.valueOf(19.90) : request.originalPrice(),
                request.maxBuy() == null ? 1 : Math.max(1, request.maxBuy()),
                Boolean.TRUE.equals(request.requireRechargeAccount()),
                accountTypes,
                defaultText(request.priceTemplateId(), "retail-default"),
                defaultText(request.priceMode(), "FIXED"),
                request.priceCoefficient() == null ? BigDecimal.ONE : request.priceCoefficient(),
                request.priceFixedAdd() == null ? BigDecimal.ZERO : request.priceFixedAdd(),
                request.stock() == null ? 5000 : request.stock(),
                0,
                defaultText(request.status(), "ON_SALE"),
                normalizeGoodsTags(request.tags()),
                now,
                now,
                normalizePlatforms(request.availablePlatforms()),
                normalizePlatforms(request.forbiddenPlatforms()),
                boundCardKindId
            );
            goods.put(id, item);
            GoodsItem refreshed = refreshStock(item);
            persistGoodsSnapshot(refreshed);
            return refreshed;
        }
    }

    public GoodsItem updateGoods(Long id, CreateGoodsRequest request) {
        synchronized (goodsLock) {
            GoodsItem current = findGoodsSnapshot(id).orElse(null);
            if (current == null) {
                throw new IllegalArgumentException("goods not found");
            }
            Long categoryId = request.categoryId() == null ? current.categoryId() : request.categoryId();
            CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
            GoodsType type = request.type() == null ? current.type() : request.type();
            Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), current.cardKindId());
            List<String> accountTypes = request.accountTypes() == null
                ? current.accountTypes()
                : (type == GoodsType.CARD ? List.of() : validateEnabledRechargeFieldCodes(request.accountTypes()));
            String nextGoodsName = firstText(request.goodsName(), request.name(), current.goodsName());
            String nextName = firstText(request.name(), request.goodsName(), current.name());
            String nextPriceLimitText = request.priceLimitText() == null
                ? defaultText(current.priceLimitText(), "")
                : normalizedPriceLimitText(request.priceLimitText(), false, nextGoodsName, nextName);
            GoodsItem next = new GoodsItem(
                current.id(),
                categoryId,
                category == null ? current.categoryName() : category.name(),
                nextGoodsName,
                nextName,
                defaultText(request.subTitle(), current.subTitle()),
                defaultText(request.description(), current.description()),
                request.benefitDurations() == null ? current.benefitDurations() : normalizeTextList(request.benefitDurations()),
                request.benefitType() == null ? current.benefitType() : defaultText(request.benefitType(), ""),
                request.benefitBrand() == null ? current.benefitBrand() : defaultText(request.benefitBrand(), ""),
                StringUtils.hasText(nextPriceLimitText),
                nextPriceLimitText,
                defaultText(request.coverUrl(), current.coverUrl()),
                request.detailImages() == null ? current.detailImages() : normalizeImages(request.detailImages()),
                request.detailBlocks() == null ? current.detailBlocks() : normalizeDetailBlocks(request.detailBlocks()),
                request.integrations() == null ? current.integrations() : normalizeIntegrations(request.integrations()),
                request.pollingEnabled() == null ? current.pollingEnabled() : request.pollingEnabled(),
                request.monitoringEnabled() == null ? current.monitoringEnabled() : request.monitoringEnabled(),
                type,
                defaultText(request.platform(), current.platform()),
                request.price() == null ? current.price() : request.price(),
                request.originalPrice() == null ? current.originalPrice() : request.originalPrice(),
                request.maxBuy() == null ? current.maxBuy() : Math.max(1, request.maxBuy()),
                request.requireRechargeAccount() == null ? current.requireRechargeAccount() : request.requireRechargeAccount(),
                accountTypes,
                defaultText(request.priceTemplateId(), current.priceTemplateId()),
                defaultText(request.priceMode(), current.priceMode()),
                request.priceCoefficient() == null ? current.priceCoefficient() : request.priceCoefficient(),
                request.priceFixedAdd() == null ? current.priceFixedAdd() : request.priceFixedAdd(),
                request.stock() == null ? current.stock() : request.stock(),
                current.sales(),
                defaultText(request.status(), current.status()),
                request.tags() == null ? normalizeGoodsTags(current.tags()) : normalizeGoodsTags(request.tags()),
                current.createdAt(),
                OffsetDateTime.now(),
                request.availablePlatforms() == null ? current.availablePlatforms() : normalizePlatforms(request.availablePlatforms()),
                request.forbiddenPlatforms() == null ? current.forbiddenPlatforms() : normalizePlatforms(request.forbiddenPlatforms()),
                boundCardKindId
            );
            goods.put(id, next);
            GoodsItem refreshed = refreshStock(next);
            persistGoodsSnapshot(refreshed);
            return refreshed;
        }
    }

    GoodsItem withEffectivePrice(GoodsItem item, Long userGroupId) {
        if (item == null || !"DYNAMIC".equalsIgnoreCase(defaultText(item.priceMode(), ""))) {
            return item;
        }
        UserGroupItem group = findUserGroupSnapshot(userGroupId == null ? 1L : userGroupId).orElse(null);
        if (group == null) {
            return item;
        }
        PriceTemplateItem template;
        synchronized (priceTemplateLock) {
            ensurePriceTemplatesReadyLocked();
            template = priceTemplates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.enabled()))
                .filter(candidate -> Objects.equals(candidate.id(), item.priceTemplateId()))
                .findFirst()
                .orElse(null);
        }
        if (template == null) {
            return item;
        }
        PriceGroupRateItem rate = template.groupRates().stream()
            .filter(candidate -> Objects.equals(normalize(candidate.groupName()), normalize(group.name())))
            .findFirst()
            .orElse(null);
        if (rate == null || rate.value() == null) {
            return item;
        }
        BigDecimal basePrice = defaultDecimal(item.price());
        BigDecimal effectivePrice = "fixed".equalsIgnoreCase(template.adjustMode())
            ? basePrice.add(rate.value())
            : basePrice.multiply(rate.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        effectivePrice = effectivePrice.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return copyGoodsWithPrice(item, effectivePrice);
    }

    private GoodsItem copyGoodsWithPrice(GoodsItem item, BigDecimal price) {
        return new GoodsItem(
            item.id(), item.categoryId(), item.categoryName(), item.goodsName(), item.name(), item.subTitle(),
            item.description(), item.benefitDurations(), item.benefitType(), item.benefitBrand(), item.priceLimited(),
            item.priceLimitText(), item.coverUrl(), item.detailImages(), item.detailBlocks(), item.integrations(),
            item.pollingEnabled(), item.monitoringEnabled(), item.type(), item.platform(), price, item.originalPrice(),
            item.maxBuy(), item.requireRechargeAccount(), item.accountTypes(), item.priceTemplateId(), item.priceMode(),
            item.priceCoefficient(), item.priceFixedAdd(), item.stock(), item.sales(), item.status(), item.tags(),
            item.createdAt(), item.updatedAt(), item.availablePlatforms(), item.forbiddenPlatforms(), item.cardKindId()
        );
    }

    void validateGoodsSalePlatform(GoodsItem item, String platform) {
        if (!goodsAllowsPlatform(item, platform)) {
            throw new IllegalStateException("该商品未开放当前端购买。");
        }
    }

    private boolean goodsAllowsPlatform(GoodsItem item, String platform) {
        String normalizedPlatform = normalizeSalePlatform(platform);
        if (!StringUtils.hasText(normalizedPlatform)) {
            return false;
        }
        List<String> available = normalizeSalePlatforms(item.availablePlatforms());
        List<String> forbidden = normalizeSalePlatforms(item.forbiddenPlatforms());
        boolean hasSalesTerminalRestriction = available.stream().anyMatch(SALES_TERMINAL_PLATFORMS::contains);
        return !platformListContains(forbidden, normalizedPlatform)
            && (!hasSalesTerminalRestriction || available.contains("all") || platformListContains(available, normalizedPlatform));
    }

    private boolean platformListContains(List<String> platforms, String platform) {
        if (platforms.contains(platform)) {
            return true;
        }
        return "web".equals(platform) && platforms.contains("pc");
    }

    private void persistCategorySnapshot(CategoryItem category) {
        if (catalogPersistenceStore == null || category == null) {
            return;
        }
        try {
            catalogPersistenceStore.saveCategorySnapshot(category);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CATEGORY", String.valueOf(category.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void deletePersistentCategory(Long id) {
        if (catalogPersistenceStore == null || id == null) {
            return;
        }
        try {
            catalogPersistenceStore.deleteCategory(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CATEGORY", String.valueOf(id), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void persistGoodsSnapshot(GoodsItem goods) {
        if (catalogPersistenceStore == null || goods == null) {
            return;
        }
        try {
            catalogPersistenceStore.saveGoodsSnapshot(goods);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS", String.valueOf(goods.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void deletePersistentGoods(Long id) {
        if (catalogPersistenceStore == null || id == null) {
            return;
        }
        try {
            catalogPersistenceStore.deleteGoods(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS", String.valueOf(id), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void persistCardKind(CardKindItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveCardKind(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CARD_KIND", String.valueOf(item.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void persistRechargeField(RechargeFieldItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveRechargeField(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "RECHARGE_FIELD", String.valueOf(item.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void deletePersistentRechargeField(Long id) {
        if (configPersistenceStore == null || id == null) {
            return;
        }
        try {
            configPersistenceStore.deleteRechargeField(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "RECHARGE_FIELD", String.valueOf(id), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    void persistSupplier(SupplierItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveSupplier(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SUPPLIER", String.valueOf(item.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    void deletePersistentSupplier(Long id) {
        if (configPersistenceStore == null || id == null) {
            return;
        }
        try {
            configPersistenceStore.deleteSupplier(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SUPPLIER", String.valueOf(id), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    void persistGoodsChannel(GoodsChannelItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveGoodsChannel(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS_CHANNEL", String.valueOf(item.id()), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void deletePersistentGoodsChannel(Long id) {
        if (configPersistenceStore == null || id == null) {
            return;
        }
        try {
            configPersistenceStore.deleteGoodsChannel(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS_CHANNEL", String.valueOf(id), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void deletePersistentGoodsChannelsByGoods(Long goodsId) {
        if (configPersistenceStore == null || goodsId == null) {
            return;
        }
        try {
            configPersistenceStore.deleteGoodsChannelsByGoods(goodsId);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS_CHANNEL", String.valueOf(goodsId), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void persistPriceTemplates() {
        List<PriceTemplateItem> snapshot;
        synchronized (priceTemplateLock) {
            ensurePriceTemplatesReadyLocked();
            snapshot = sanitizedPriceTemplatesSnapshotLocked();
        }
        persistPriceTemplates(snapshot);
    }

    private void persistPriceTemplates(List<PriceTemplateItem> snapshot) {
        try {
            configService.savePriceTemplatesJson(OBJECT_MAPPER.writeValueAsString(snapshot));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PRICE_TEMPLATE", "LIST", ex.getMessage());
            throw new IllegalStateException("price template serialization failed", ex);
        }
    }

    private Optional<List<CategoryItem>> persistentCategories() {
        if (catalogPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<CategoryItem> items = catalogPersistenceStore.listCategories();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "CATEGORY", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<GoodsItem>> persistentGoods() {
        if (catalogPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<GoodsItem> items = catalogPersistenceStore.listGoods();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "GOODS", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<CardKindItem>> persistentCardKinds() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<CardKindItem> items = configPersistenceStore.listCardKinds();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "CARD_KIND", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<RechargeFieldItem>> persistentRechargeFields() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<RechargeFieldItem> items = configPersistenceStore.listRechargeFields();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "RECHARGE_FIELD", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    Optional<List<SupplierItem>> persistentSuppliers() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<SupplierItem> items = configPersistenceStore.listSuppliers();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SUPPLIER", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<GoodsChannelItem>> persistentGoodsChannels() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<GoodsChannelItem> items = configPersistenceStore.listGoodsChannels();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "GOODS_CHANNEL", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    void loadPriceTemplates() {
        synchronized (priceTemplateLock) {
            loadPriceTemplatesLocked();
        }
    }

    private void loadPriceTemplatesLocked() {
        if (configPersistenceStore == null) {
            priceTemplates.clear();
            priceTemplates.add(defaultPriceTemplate());
            return;
        }
        try {
            String raw = configService.priceTemplatesJson();
            if (!StringUtils.hasText(raw)) {
                priceTemplates.clear();
                priceTemplates.add(defaultPriceTemplate());
                persistPriceTemplates(sanitizedPriceTemplatesSnapshotLocked());
                return;
            }
            PriceTemplateItem[] items = OBJECT_MAPPER.readValue(raw, PriceTemplateItem[].class);
            priceTemplates.clear();
            for (PriceTemplateItem item : items) {
                PriceTemplateItem next = sanitizePriceTemplate(item);
                if (StringUtils.hasText(next.id()) && StringUtils.hasText(next.name())) {
                    priceTemplates.add(next);
                }
            }
            if (priceTemplates.isEmpty()) {
                priceTemplates.add(defaultPriceTemplate());
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "PRICE_TEMPLATE", "LIST", ex.getMessage());
            priceTemplates.clear();
            priceTemplates.add(defaultPriceTemplate());
        }
    }

    private void ensurePriceTemplatesReady() {
        synchronized (priceTemplateLock) {
            ensurePriceTemplatesReadyLocked();
        }
    }

    private void ensurePriceTemplatesReadyLocked() {
        if (priceTemplates.isEmpty()) {
            loadPriceTemplatesLocked();
        }
    }

    private List<PriceTemplateItem> sanitizedPriceTemplatesSnapshotLocked() {
        return priceTemplates.stream()
            .map(this::sanitizePriceTemplate)
            .toList();
    }

    private long maxCardKindId() {
        long max = cardKinds.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<CardKindItem>> persistent = persistentCardKinds();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(CardKindItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }

    private long maxCategoryId() {
        long max = categories.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<CategoryItem>> persistent = persistentCategories();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(CategoryItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }

    private long maxRechargeFieldId() {
        long max = rechargeFields.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<RechargeFieldItem>> persistent = persistentRechargeFields();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(RechargeFieldItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }

    private long maxGoodsChannelId() {
        long max = goodsChannels.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<GoodsChannelItem>> persistent = persistentGoodsChannels();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(GoodsChannelItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }

    private long maxGoodsId() {
        long max = goods.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<GoodsItem>> persistent = persistentGoods();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(GoodsItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }

    private Optional<RechargeFieldItem> findRechargeFieldSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        RechargeFieldItem memory = rechargeFields.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<RechargeFieldItem> persistent = persistentRechargeFields().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> rechargeFields.put(item.id(), item));
        return persistent;
    }

    private boolean rechargeFieldCodeExists(String code, Long excludeId) {
        String normalizedCode = normalizeRechargeFieldCode(code);
        if (!StringUtils.hasText(normalizedCode)) {
            return false;
        }
        boolean memoryMatch = rechargeFields.values().stream()
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(item.code(), normalizedCode));
        if (memoryMatch) {
            return true;
        }
        return persistentRechargeFields().stream()
            .flatMap(List::stream)
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(item.code(), normalizedCode));
    }

    Optional<GoodsItem> findGoodsSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        GoodsItem memory = goods.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<GoodsItem> persistent = persistentGoods().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> goods.put(item.id(), item));
        return persistent;
    }

    private List<CategoryItem> allCategorySnapshots() {
        Map<Long, CategoryItem> snapshots = new java.util.LinkedHashMap<>();
        persistentCategories().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        categories.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .toList();
    }

    Optional<CategoryItem> findCategorySnapshot(Long id) {
        if (id == null || id == 0L) {
            return Optional.empty();
        }
        CategoryItem memory = categories.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<CategoryItem> persistent = persistentCategories().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> categories.put(item.id(), item));
        return persistent;
    }

    List<GoodsItem> allGoodsSnapshots() {
        Map<Long, GoodsItem> snapshots = new java.util.LinkedHashMap<>();
        persistentGoods().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        goods.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    List<GoodsChannelItem> allGoodsChannelSnapshots() {
        Map<Long, GoodsChannelItem> snapshots = new java.util.LinkedHashMap<>();
        persistentGoodsChannels().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        goodsChannels.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(GoodsChannelItem::id))
            .toList();
    }

    Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        GoodsChannelItem memory = goodsChannels.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<GoodsChannelItem> persistent = persistentGoodsChannels().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> goodsChannels.put(item.id(), item));
        return persistent;
    }

    private PriceTemplateItem sanitizePriceTemplate(PriceTemplateItem item) {
        PriceTemplateItem source = item == null ? defaultPriceTemplate() : item;
        String id = normalize(defaultText(source.id(), ""));
        if (!StringUtils.hasText(id)) {
            id = "tpl-" + System.currentTimeMillis();
        }
        List<PriceGroupRateItem> rates = source.groupRates() == null ? List.of() : source.groupRates().stream()
            .filter(Objects::nonNull)
            .map(rate -> new PriceGroupRateItem(
                requiredText(rate.groupName(), "默认会员"),
                defaultText(rate.color(), "#12a594"),
                rate.value() == null ? BigDecimal.valueOf(100) : rate.value()
            ))
            .toList();
        if (rates.isEmpty()) {
            rates = defaultPriceTemplate().groupRates();
        }
        return new PriceTemplateItem(
            id,
            requiredText(source.name(), "价格模板"),
            "fixed".equalsIgnoreCase(defaultText(source.adjustMode(), "")) ? "fixed" : "percent",
            source.referencePrice() == null ? BigDecimal.valueOf(100) : source.referencePrice(),
            rates,
            source.enabled() == null || source.enabled()
        );
    }

    private PriceTemplateItem defaultPriceTemplate() {
        return new PriceTemplateItem(
            "retail-default",
            "默认加价模板",
            "percent",
            BigDecimal.valueOf(100),
            List.of(
                new PriceGroupRateItem("默认会员", "#ffb300", BigDecimal.valueOf(110)),
                new PriceGroupRateItem("渠道 VIP", "#3aa5ff", BigDecimal.valueOf(108)),
                new PriceGroupRateItem("受限会员", "#12a594", BigDecimal.valueOf(106))
            ),
            true
        );
    }

    GoodsItem refreshStock(GoodsItem item) {
        if (item.type() != GoodsType.CARD) {
            return item;
        }
        return item.withStock(item.cardKindId() == null ? availableCardCount(item.id()) : availableCardKindCardCount(item.cardKindId()));
    }

    /**
     * 把 CARD 商品的标称库存对齐到真实可售卡密数。
     *
     * <p>持久化模式下用<b>定点 UPDATE</b>（只改 stock_count）而不是整行快照 upsert。
     * 整行 upsert 会把内存里可能已经过期的价格、状态等字段一起绝对覆盖回库，
     * 这正是"双扣"那类脏缓存事故的同源写法。
     */
    void refreshGoodsStock(Long targetGoodsId) {
        if (fundsLedgerEnabled()) {
            GoodsItem item = findGoodsSnapshot(targetGoodsId).orElse(null);
            if (item != null && item.type() == GoodsType.CARD) {
                gateway.syncCardGoodsStock(item.id(), item.cardKindId());
                goods.remove(item.id());
            }
            return;
        }
        GoodsItem item = findGoodsSnapshot(targetGoodsId).orElse(null);
        if (item != null && item.type() == GoodsType.CARD) {
            GoodsItem refreshed = refreshStock(item);
            goods.put(item.id(), refreshed);
            persistGoodsSnapshot(refreshed);
        }
    }

    void refreshGoodsStockForCardKind(Long targetCardKindId) {
        if (fundsLedgerEnabled()) {
            allGoodsSnapshots().stream()
                .filter(item -> Objects.equals(item.cardKindId(), targetCardKindId))
                .forEach(item -> {
                    gateway.syncCardGoodsStock(item.id(), targetCardKindId);
                    goods.remove(item.id());
                });
            return;
        }
        goods.values().stream()
            .filter(item -> Objects.equals(item.cardKindId(), targetCardKindId))
            .forEach(item -> {
                GoodsItem refreshed = refreshStock(item);
                goods.put(item.id(), refreshed);
                persistGoodsSnapshot(refreshed);
            });
    }

    private boolean containsKeyword(GoodsItem item, String keyword) {
        if (keyword.chars().allMatch(Character::isDigit)) {
            return String.valueOf(item.id()).equals(keyword);
        }
        return normalize(item.goodsName()).contains(keyword)
            || normalize(item.name()).contains(keyword)
            || normalize(item.subTitle()).contains(keyword)
            || normalize(item.description()).contains(keyword)
            || normalize(item.platform()).contains(keyword);
    }

    Set<Long> categoryTreeIds(Long rootId) {
        Set<Long> result = new LinkedHashSet<>();
        collectCategoryTreeIds(rootId, result);
        return result;
    }

    private void collectCategoryTreeIds(Long parentId, Set<Long> result) {
        if (!result.add(parentId)) {
            return;
        }
        allCategorySnapshots().stream()
            .filter(category -> Objects.equals(category.parentId(), parentId))
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .forEach(category -> collectCategoryTreeIds(category.id(), result));
    }

    private CategoryItem enrichCategory(CategoryItem item) {
        return enrichCategory(item, null, null);
    }

    private CategoryItem enrichCategory(CategoryItem item, Map<Long, CategoryItem> categorySnapshot, Set<Long> parentIds) {
        boolean enabled = item.enabled() == null || item.enabled();
        return new CategoryItem(
            item.id(),
            item.name(),
            item.nickname(),
            item.parentId(),
            item.icon(),
            item.iconUrl(),
            item.customIconUrl(),
            item.sort(),
            enabled,
            categoryStatus(enabled),
            categoryLevel(item.parentId(), categorySnapshot) + 1,
            parentIds == null ? hasChildCategory(item.id()) : parentIds.contains(item.id())
        );
    }

    private CardKindItem enrichCardKind(CardKindItem item) {
        int total = gateway.cardKindTotalCount(item.id());
        int available = availableCardKindCardCount(item.id());
        int used = gateway.cardKindUsedCount(item.id());
        return new CardKindItem(
            item.id(),
            item.name(),
            item.type(),
            item.cost(),
            total,
            available,
            used
        );
    }

    private void validateCategoryParent(Long id, Long parentId) {
        Long nextParentId = parentId == null ? 0L : parentId;
        if (Objects.equals(id, nextParentId)) {
            throw new IllegalArgumentException("category cannot be moved under itself");
        }
        if (nextParentId != 0L && findCategorySnapshot(nextParentId).isEmpty()) {
            throw new IllegalArgumentException("parent category not found");
        }
        if (nextParentId != 0L && categoryTreeIds(id).contains(nextParentId)) {
            throw new IllegalArgumentException("category cannot be moved under its descendant");
        }
    }

    private int categorySubtreeHeight(Long id) {
        return allCategorySnapshots().stream()
            .filter(category -> Objects.equals(category.parentId(), id))
            .mapToInt(category -> categorySubtreeHeight(category.id()) + 1)
            .max()
            .orElse(1);
    }

    private boolean hasChildCategory(Long id) {
        return allCategorySnapshots().stream().anyMatch(category -> Objects.equals(category.parentId(), id));
    }

    private boolean categoryEnabled(Boolean enabled, String status) {
        if (enabled != null) {
            return enabled;
        }
        if (!StringUtils.hasText(status)) {
            return true;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        return !List.of("DISABLED", "DISABLE", "OFF", "INACTIVE", "FALSE", "0").contains(normalizedStatus);
    }

    private String categoryStatus(boolean enabled) {
        return enabled ? "ENABLED" : "DISABLED";
    }

    private String normalizeCategoryIcon(String icon) {
        return StringUtils.hasText(icon) ? icon.trim() : "";
    }

    private String normalizeCardKindType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("card kind type is required");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ONCE", "REUSABLE").contains(normalized)) {
            throw new IllegalArgumentException("card kind type must be ONCE or REUSABLE");
        }
        return normalized;
    }

    private Long normalizeGoodsCardKindId(GoodsType type, Long requestedCardKindId, Long currentCardKindId) {
        if (type != GoodsType.CARD) {
            if (requestedCardKindId != null) {
                throw new IllegalArgumentException("card kind can only be bound to card goods");
            }
            return null;
        }
        Long nextCardKindId = requestedCardKindId == null ? currentCardKindId : requestedCardKindId;
        if (nextCardKindId == null) {
            return null;
        }
        if (!cardKinds.containsKey(nextCardKindId)) {
            throw new IllegalArgumentException("card kind not found");
        }
        return nextCardKindId;
    }

    List<String> normalizePlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return List.of();
        }
        List<String> normalized = platforms.stream()
            .map(this::normalize)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        return normalized;
    }

    private List<String> normalizeSalePlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return List.of();
        }
        return platforms.stream()
            .map(this::normalizeSalePlatform)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    String normalizeSalePlatform(String platform) {
        String normalized = normalize(platform);
        return switch (normalized) {
            case "pc" -> "web";
            case "member-api", "member_api" -> "api";
            default -> normalized;
        };
    }

    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private List<GoodsDetailBlock> normalizeDetailBlocks(List<GoodsDetailBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
            .filter(Objects::nonNull)
            .map(block -> {
                String type = defaultText(block.type(), StringUtils.hasText(block.imageUrl()) ? "image" : "text").trim();
                String imageUrl = block.imageUrl() == null ? "" : block.imageUrl().trim();
                String text = block.text() == null ? "" : block.text().trim();
                return new GoodsDetailBlock(type, imageUrl, text);
            })
            .filter(block -> StringUtils.hasText(block.imageUrl()) || StringUtils.hasText(block.text()))
            .toList();
    }

    List<GoodsIntegrationItem> normalizeIntegrations(List<GoodsIntegrationItem> integrations) {
        if (integrations == null || integrations.isEmpty()) {
            return List.of();
        }
        return integrations.stream()
            .filter(Objects::nonNull)
            .map(item -> new GoodsIntegrationItem(
                defaultText(item.id(), UUID.randomUUID().toString()),
                item.supplierId(),
                defaultText(item.supplierName(), ""),
                normalize(item.platformCode()),
                defaultText(item.supplierGoodsId(), ""),
                defaultText(item.supplierGoodsName(), ""),
                item.supplierPrice() == null ? BigDecimal.ZERO : item.supplierPrice(),
                defaultText(item.upstreamStatus(), "正常"),
                item.upstreamStock() == null ? 0 : item.upstreamStock(),
                defaultText(item.upstreamTitle(), item.supplierGoodsName()),
                defaultText(item.lastSyncAt(), OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                true
            ))
            .filter(item -> StringUtils.hasText(item.platformCode()) || StringUtils.hasText(item.supplierGoodsId()))
            .toList();
    }

    private GoodsItem withChannelIntegrations(GoodsItem item) {
        return withChannelIntegrations(item, allGoodsChannelSnapshots());
    }

    private GoodsItem withChannelIntegrations(GoodsItem item, List<GoodsChannelItem> channelSnapshots) {
        if (item == null || item.id() == null) {
            return item;
        }
        List<GoodsIntegrationItem> savedIntegrations = normalizeIntegrations(item.integrations());
        Set<String> savedIntegrationKeys = savedIntegrations.stream()
            .map(this::integrationKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<GoodsIntegrationItem> channelIntegrations = channelSnapshots.stream()
            .filter(channel -> Objects.equals(channel.goodsId(), item.id()))
            .sorted(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .map(this::goodsChannelIntegration)
            .filter(integration -> savedIntegrationKeys.isEmpty() || savedIntegrationKeys.contains(integrationKey(integration)))
            .toList();
        if (channelIntegrations.isEmpty() && savedIntegrations.isEmpty()) {
            return item;
        }

        Map<String, GoodsIntegrationItem> merged = new LinkedHashMap<>();
        savedIntegrations.forEach(integration -> merged.put(integrationKey(integration), integration));
        channelIntegrations.forEach(integration -> {
            String key = integrationKey(integration);
            if (isFallbackChannelIntegration(integration) && merged.containsKey(key)) {
                return;
            }
            merged.put(key, integration);
        });
        return item.withIntegrations(List.copyOf(merged.values()));
    }

    private boolean isFallbackChannelIntegration(GoodsIntegrationItem integration) {
        return integration != null
            && integration.supplierPrice().compareTo(BigDecimal.ZERO) == 0
            && Objects.equals(integration.upstreamStock(), 0)
            && Objects.equals(defaultText(integration.supplierGoodsName(), ""), defaultText(integration.supplierGoodsId(), ""));
    }

    boolean integrationChanged(GoodsIntegrationItem oldItem, GoodsIntegrationItem nextItem) {
        if (oldItem == null) {
            return true;
        }
        return oldItem.supplierPrice().compareTo(nextItem.supplierPrice()) != 0
            || !Objects.equals(oldItem.upstreamStock(), nextItem.upstreamStock())
            || !Objects.equals(defaultText(oldItem.supplierGoodsName(), ""), defaultText(nextItem.supplierGoodsName(), ""))
            || !Objects.equals(defaultText(oldItem.upstreamStatus(), ""), defaultText(nextItem.upstreamStatus(), ""));
    }

    private synchronized void ensureGoodsChannelsForIntegrations(List<GoodsItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<GoodsChannelItem> channelSnapshots = allGoodsChannelSnapshots();
        Set<String> existingKeys = new LinkedHashSet<>();
        channelSnapshots.forEach(channel -> existingKeys.add(goodsChannelKey(channel.goodsId(), channel.supplierId(), channel.supplierGoodsId())));

        OffsetDateTime now = OffsetDateTime.now();
        for (GoodsItem item : items) {
            if (item == null || item.id() == null || item.type() != GoodsType.DIRECT) {
                continue;
            }
            List<GoodsIntegrationItem> integrations = normalizeIntegrations(item.integrations());
            if (integrations.isEmpty()) {
                continue;
            }
            Set<String> desiredKeys = integrations.stream()
                .filter(integration -> !Boolean.FALSE.equals(integration.enabled()))
                .filter(integration -> integration.supplierId() != null && StringUtils.hasText(integration.supplierGoodsId()))
                .map(integration -> goodsChannelKey(item.id(), integration.supplierId(), integration.supplierGoodsId()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            channelSnapshots.stream()
                .filter(channel -> Objects.equals(channel.goodsId(), item.id()))
                .filter(channel -> !desiredKeys.contains(goodsChannelKey(channel.goodsId(), channel.supplierId(), channel.supplierGoodsId())))
                .forEach(channel -> {
                    goodsChannels.remove(channel.id());
                    gateway.forgetMonitorChannel(channel.id());
                    deletePersistentGoodsChannel(channel.id());
                    existingKeys.remove(goodsChannelKey(channel.goodsId(), channel.supplierId(), channel.supplierGoodsId()));
                    appendOperation("GOODS_CHANNEL_REPAIR_DELETE", "GOODS", String.valueOf(item.id()), channel.supplierGoodsId());
                });
            for (GoodsIntegrationItem integration : integrations) {
                if (Boolean.FALSE.equals(integration.enabled()) || integration.supplierId() == null || !StringUtils.hasText(integration.supplierGoodsId())) {
                    continue;
                }
                String key = goodsChannelKey(item.id(), integration.supplierId(), integration.supplierGoodsId());
                if (existingKeys.contains(key)) {
                    continue;
                }
                GoodsChannelItem channel = new GoodsChannelItem(
                    allocateIncrementingId(channelId, maxGoodsChannelId()),
                    item.id(),
                    integration.supplierId(),
                    firstText(
                        gateway.supplierSnapshot(integration.supplierId()).map(SupplierItem::name).orElse(""),
                        integration.supplierName(),
                        "货源渠道"
                    ),
                    integration.supplierGoodsId(),
                    10,
                    30,
                    "ENABLED",
                    now
                );
                goodsChannels.put(channel.id(), channel);
                persistGoodsChannel(channel);
                existingKeys.add(key);
                appendOperation("GOODS_CHANNEL_REPAIR", "GOODS", String.valueOf(item.id()), integration.supplierGoodsId());
            }
        }
    }

    private String goodsChannelKey(Long goodsId, Long supplierId, String supplierGoodsId) {
        return defaultText(goodsId == null ? "" : String.valueOf(goodsId), "")
            + ":" + defaultText(supplierId == null ? "" : String.valueOf(supplierId), "")
            + ":" + defaultText(supplierGoodsId, "").trim();
    }

    private GoodsIntegrationItem goodsChannelIntegration(GoodsChannelItem channel) {
        SupplierItem supplier = gateway.supplierSnapshot(channel.supplierId()).orElse(null);
        String supplierName = firstText(supplier == null ? "" : supplier.name(), channel.supplierName(), "货源渠道");
        String platformCode = supplier == null ? String.valueOf(channel.supplierId()) : defaultText(supplier.platformType(), String.valueOf(channel.supplierId()));
        Optional<GoodsIntegrationItem> remote = supplier == null
            ? Optional.empty()
            : gateway.cachedRemoteIntegration(supplier, channel.supplierGoodsId());
        if (supplier != null && remote.isPresent()) {
            GoodsIntegrationItem snapshot = remote.get();
            return new GoodsIntegrationItem(
                "channel-" + channel.id(),
                snapshot.supplierId(),
                snapshot.supplierName(),
                snapshot.platformCode(),
                snapshot.supplierGoodsId(),
                snapshot.supplierGoodsName(),
                snapshot.supplierPrice(),
                snapshot.upstreamStatus(),
                snapshot.upstreamStock(),
                snapshot.upstreamTitle(),
                snapshot.lastSyncAt(),
                "ENABLED".equals(channel.status()) && snapshot.enabled()
            );
        }
        return new GoodsIntegrationItem(
            "channel-" + channel.id(),
            channel.supplierId(),
            supplierName,
            platformCode,
            defaultText(channel.supplierGoodsId(), ""),
            defaultText(channel.supplierGoodsId(), ""),
            BigDecimal.ZERO,
            defaultText(channel.status(), "ENABLED"),
            0,
            defaultText(channel.supplierGoodsId(), ""),
            channel.createdAt() == null ? "" : channel.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "ENABLED".equals(channel.status())
        );
    }

    List<String> normalizeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private List<String> normalizeGoodsTags(List<String> values) {
        return normalizeTextList(values).stream()
            .filter(value -> !LEGACY_SYSTEM_GOODS_TAGS.contains(value.toLowerCase(Locale.ROOT)))
            .toList();
    }

    String normalizeRechargeFieldCode(String value) {
        return normalize(value)
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private boolean isValidRechargeFieldCode(String value) {
        return value != null && value.matches("^[a-z][a-z0-9_]*$");
    }

    List<String> validateEnabledRechargeFieldCodes(List<String> codes) {
        List<String> normalizedCodes = normalizeTextList(codes).stream()
            .map(this::normalizeRechargeFieldCode)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (normalizedCodes.isEmpty()) {
            return List.of();
        }
        Set<String> enabledCodes = listRechargeFields(true).stream()
            .map(RechargeFieldItem::code)
            .collect(java.util.stream.Collectors.toSet());
        List<String> invalidCodes = normalizedCodes.stream()
            .filter(code -> !enabledCodes.contains(code))
            .toList();
        if (!invalidCodes.isEmpty()) {
            throw new IllegalArgumentException("充值字段不存在或已停用: " + String.join(", ", invalidCodes));
        }
        return normalizedCodes;
    }

    List<String> normalizedBenefitDurations(List<String> durations, String title) {
        List<String> normalized = normalizeTextList(durations);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return inferredBenefitDurations(title);
    }

    private Boolean normalizedPriceLimited(Boolean explicitValue, String... titles) {
        if (explicitValue != null) {
            return explicitValue;
        }
        for (String title : titles) {
            if (titleContainsPriceLimited(title)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedPriceLimitText(String explicitValue, boolean allowInfer, String... titles) {
        if (explicitValue != null) {
            return defaultText(explicitValue, "").trim();
        }
        return allowInfer ? inferredPriceLimitText(titles) : "";
    }

    String inferredPriceLimitText(String... titles) {
        for (String title : titles) {
            String value = inferPriceLimitFromTitle(title);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String inferPriceLimitFromTitle(String title) {
        String cleanTitle = defaultText(title, "").trim();
        if (!StringUtils.hasText(cleanTitle)) {
            return "";
        }
        Matcher matcher = PRICE_LIMIT_PATTERN.matcher(cleanTitle);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\s+", "");
        }
        return titleContainsPriceLimited(cleanTitle) ? "限价" : "";
    }

    boolean titleContainsPriceLimited(String title) {
        String normalizedTitle = normalize(defaultText(title, ""));
        return StringUtils.hasText(normalizedTitle)
            && (normalizedTitle.contains("限价")
                || normalizedTitle.contains("限 价")
                || normalizedTitle.contains("限制售价")
                || normalizedTitle.contains("限定价格")
                || normalizedTitle.contains("控价"));
    }

    private List<String> inferredBenefitDurations(String title) {
        String normalizedTitle = normalize(defaultText(title, ""));
        if (!StringUtils.hasText(normalizedTitle)) {
            return List.of();
        }
        if (normalizedTitle.contains("15天") || normalizedTitle.contains("十五天") || normalizedTitle.contains("半月") || normalizedTitle.contains("半个月")) {
            return List.of("半月");
        }
        if (normalizedTitle.contains("12个月") || normalizedTitle.contains("十二个月") || normalizedTitle.contains("年卡") || normalizedTitle.contains("一年")) {
            return List.of("一年");
        }
        if (normalizedTitle.contains("半年") || normalizedTitle.contains("6个月") || normalizedTitle.contains("六个月")) {
            return List.of("半年");
        }
        if (normalizedTitle.contains("3个月") || normalizedTitle.contains("三个月") || normalizedTitle.contains("季卡")) {
            return List.of("季卡");
        }
        if (normalizedTitle.contains("1个月") || normalizedTitle.contains("一个月") || normalizedTitle.contains("月卡")) {
            return List.of("月卡");
        }
        if (normalizedTitle.contains("7天") || normalizedTitle.contains("七天") || normalizedTitle.contains("周卡") || normalizedTitle.contains("一周")) {
            return List.of("周卡");
        }
        if (normalizedTitle.contains("3天") || normalizedTitle.contains("三天")) {
            return List.of("三天");
        }
        if (normalizedTitle.contains("1天") || normalizedTitle.contains("一天") || normalizedTitle.contains("日卡")) {
            return List.of("一天");
        }
        return List.of();
    }

    String normalizeRechargeFieldInputType(String value) {
        String normalized = normalize(value);
        if (List.of("text", "number", "mobile", "email", "textarea", "qq", "jianying_id", "douyin_id").contains(normalized)) {
            return normalized;
        }
        return "text";
    }

    Optional<RechargeFieldItem> rechargeFieldByCode(String code) {
        String normalizedCode = normalizeRechargeFieldCode(code);
        Optional<RechargeFieldItem> memory = rechargeFields.values().stream()
            .filter(item -> Objects.equals(item.code(), normalizedCode))
            .findFirst();
        if (memory.isPresent()) {
            return memory;
        }
        Optional<RechargeFieldItem> persistent = persistentRechargeFields().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.code(), normalizedCode))
            .findFirst();
        persistent.ifPresent(item -> rechargeFields.put(item.id(), item));
        return persistent;
    }

    void seedCategories() {
        categories.put(1L, new CategoryItem(1L, "会员权益", 0L, 10, true));
        categories.put(11L, new CategoryItem(11L, "视频平台", 1L, 11, true));
        categories.put(111L, new CategoryItem(111L, "月卡专区", 11L, 12, true));
        categories.put(1111L, new CategoryItem(1111L, "自动发卡", 111L, 13, true));
        categories.put(11111L, new CategoryItem(11111L, "会员周卡", 1111L, 14, true));
        categories.put(2L, new CategoryItem(2L, "游戏直充", 0L, 20, true));
        categories.put(22L, new CategoryItem(22L, "手游充值", 2L, 21, true));
        categories.put(222L, new CategoryItem(222L, "点券直充", 22L, 22, true));
        categories.put(2222L, new CategoryItem(2222L, "API 秒充", 222L, 23, true));
        categories.put(22222L, new CategoryItem(22222L, "热门大区", 2222L, 24, true));
        categories.put(3L, new CategoryItem(3L, "人工代办", 0L, 30, true));
        categories.put(33L, new CategoryItem(33L, "海外账号", 3L, 31, true));
        categories.put(333L, new CategoryItem(333L, "人工处理", 33L, 32, true));
    }

    void seedRechargeFields() {
        OffsetDateTime now = OffsetDateTime.now();
        rechargeFields.put(1L, new RechargeFieldItem(1L, "mobile", "手机号", "请输入充值手机号", "用于手机号直充、会员绑定等商品", "mobile", true, 10, true, now, now));
        rechargeFields.put(2L, new RechargeFieldItem(2L, "qq", "QQ号", "请输入 QQ 号", "用于 QQ 会员、黄钻等权益商品", "qq", true, 20, true, now, now));
        rechargeFields.put(3L, new RechargeFieldItem(3L, "wechat", "微信号", "请输入微信号", "用于微信生态权益或人工核验", "text", false, 30, true, now, now));
        rechargeFields.put(4L, new RechargeFieldItem(4L, "game_uid", "游戏 UID", "请输入游戏 UID", "用于游戏点券、区服角色类商品", "text", true, 40, true, now, now));
        rechargeFields.put(5L, new RechargeFieldItem(5L, "email", "邮箱", "请输入邮箱", "用于邮箱登录或海外账号类商品", "email", false, 50, true, now, now));
        rechargeFields.put(6L, new RechargeFieldItem(6L, "jianying_id", "剪映ID", "请输入剪映 ID", "用于剪映相关权益或模板服务", "jianying_id", true, 60, true, now, now));
        rechargeFields.put(7L, new RechargeFieldItem(7L, "douyin_id", "抖音ID", "请输入抖音 ID", "用于抖音账号权益或投流服务", "douyin_id", true, 70, true, now, now));
    }

    void seedGoodsChannels() {
        goodsChannels.put(30001L, new GoodsChannelItem(
            30001L,
            10002L,
            20001L,
            "星河直充",
            "STAR-GAME-60",
            10,
            30,
            "ENABLED",
            OffsetDateTime.now()
        ));
        goodsChannels.put(30002L, new GoodsChannelItem(
            30002L,
            10002L,
            20002L,
            "云桥货源",
            "BRIDGE-GAME-60",
            20,
            45,
            "ENABLED",
            OffsetDateTime.now()
        ));
    }

    private int categoryLevel(Long parentId) {
        return categoryLevel(parentId, null);
    }

    private int categoryLevel(Long parentId, Map<Long, CategoryItem> categorySnapshot) {
        if (parentId == null || parentId == 0L) {
            return 0;
        }
        CategoryItem parent = categorySnapshot == null
            ? findCategorySnapshot(parentId).orElse(null)
            : categorySnapshot.get(parentId);
        if (parent == null) {
            return 0;
        }
        return categoryLevel(parent.parentId(), categorySnapshot) + 1;
    }

    void seedGoods() {
        OffsetDateTime now = OffsetDateTime.now();
        goods.put(10001L, new GoodsItem(
            10001L,
            11111L,
            "会员周卡",
            "视频会员周卡",
            "视频会员周卡",
            "自动发卡，模拟支付后立即展示卡密",
            "适合前端联调自动发货链路的 CARD 商品。",
            List.of("周卡"),
            "影视会员",
            "演示品牌",
            false,
            "",
            "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "下单完成后自动出卡，订单详情页可查看卡密与使用说明。")),
            List.of(new GoodsIntegrationItem("link-10001-1", null, "", "douyin", "DY-VIP-WEEK", "抖音视频会员周卡", BigDecimal.valueOf(5.80), "正常", 120, "视频会员周卡", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)),
            false,
            true,
            GoodsType.CARD,
            "VIDEO",
            BigDecimal.valueOf(6.90),
            BigDecimal.valueOf(12.00),
            5,
            false,
            List.of(),
            "retail-default",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0,
            128,
            "ON_SALE",
            List.of("auto-delivery", "card"),
            now,
            now,
            List.of("h5", "web", "api", "private"),
            List.of("pdd"),
            null
        ));
        goods.put(10002L, new GoodsItem(
            10002L,
            22222L,
            "热门大区",
            "游戏点券 60 枚",
            "游戏点券 60 枚",
            "直充商品，创建后进入采购中",
            "用于验证 DIRECT 商品的下单、采购中状态和充值账号字段。",
            List.of("一天", "三天"),
            "游戏充值",
            "演示品牌",
            false,
            "",
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "直充商品会按渠道优先级自动采购，失败后自动切换备用渠道。")),
            List.of(
                new GoodsIntegrationItem("link-10002-1", null, "", "taobao", "TB-GAME-60", "淘宝游戏点券 60 枚", BigDecimal.valueOf(5.20), "正常", 999, "游戏点券 60 枚", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true),
                new GoodsIntegrationItem("link-10002-2", null, "", "pdd", "PDD-GAME-60", "拼多多点券 60 枚", BigDecimal.valueOf(5.10), "正常", 860, "游戏点券 60 枚", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)
            ),
            true,
            true,
            GoodsType.DIRECT,
            "GAME",
            BigDecimal.valueOf(5.80),
            BigDecimal.valueOf(6.00),
            1,
            true,
            List.of("mobile", "game_uid"),
            "member-standard",
            "DYNAMIC",
            BigDecimal.valueOf(1.08),
            BigDecimal.valueOf(0.20),
            999,
            245,
            "ON_SALE",
            List.of("direct", "recharge"),
            now,
            now,
            List.of("h5", "web", "api"),
            List.of(),
            null
        ));
        goods.put(10003L, new GoodsItem(
            10003L,
            333L,
            "人工处理",
            "资料人工代办服务",
            "资料人工代办服务",
            "人工处理商品，创建后等待客服处理",
            "用于验证 MANUAL 商品的待人工状态。",
            List.of("月卡"),
            "人工服务",
            "演示品牌",
            false,
            "",
            "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "代充订单由后台人工确认完成，适合需要客服处理的服务商品。")),
            List.of(new GoodsIntegrationItem("link-10003-1", null, "", "private", "PR-MANUAL-001", "私域人工代办", BigDecimal.valueOf(16.00), "正常", 50, "资料人工代办服务", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)),
            false,
            false,
            GoodsType.MANUAL,
            "SERVICE",
            BigDecimal.valueOf(19.90),
            BigDecimal.valueOf(29.90),
            1,
            true,
            List.of("mobile", "wechat"),
            "manual-service",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            50,
            32,
            "ON_SALE",
            List.of("manual", "service"),
            now,
            now,
            List.of("h5", "web", "private"),
            List.of("douyin"),
            null
        ));
    }
}
