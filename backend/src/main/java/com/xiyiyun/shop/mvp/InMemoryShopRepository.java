package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.persistence.AuditPersistenceStore;
import com.xiyiyun.shop.persistence.CatalogPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryShopRepository {
    private static final PasswordEncoder ADMIN_PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final String DEFAULT_ADMIN_PASSWORD_BCRYPT = "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq";
    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration USER_TOKEN_TTL = Duration.ofDays(30);
    private static final Duration ADMIN_TOKEN_TTL = Duration.ofHours(12);
    private static final Duration MEMBER_API_NONCE_TTL = Duration.ofMinutes(5);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };

    private final Map<Long, CategoryItem> categories = new ConcurrentHashMap<>();
    private final Map<Long, CardKindItem> cardKinds = new ConcurrentHashMap<>();
    private final Map<Long, GoodsItem> goods = new ConcurrentHashMap<>();
    private final Map<Long, CardSecret> cards = new ConcurrentHashMap<>();
    private final Map<String, OrderItem> orders = new ConcurrentHashMap<>();
    private final Map<String, PaymentItem> payments = new ConcurrentHashMap<>();
    private final Map<Long, PaymentCallbackLogItem> paymentCallbackLogs = new ConcurrentHashMap<>();
    private final Map<String, RefundItem> refunds = new ConcurrentHashMap<>();
    private final Map<Long, SmsLogItem> smsLogs = new ConcurrentHashMap<>();
    private final Map<Long, OperationLogItem> operationLogs = new ConcurrentHashMap<>();
    private final Map<Long, SupplierItem> suppliers = new ConcurrentHashMap<>();
    private final Map<Long, String> supplierApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, RemoteGoodsSyncResult> remoteGoodsSyncResults = new ConcurrentHashMap<>();
    private final Map<Long, GoodsChannelItem> goodsChannels = new ConcurrentHashMap<>();
    private final Map<Long, ProductMonitorState> productMonitorStates = new ConcurrentHashMap<>();
    private final Map<Long, ProductMonitorLogItem> productMonitorLogs = new ConcurrentHashMap<>();
    private final Map<Long, RechargeFieldItem> rechargeFields = new ConcurrentHashMap<>();
    private final Map<Long, UserGroupItem> userGroups = new ConcurrentHashMap<>();
    private final Map<Long, UserItem> users = new ConcurrentHashMap<>();
    private final Map<String, MemberApiCredentialItem> memberCredentials = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> memberNonceExpiresAt = new ConcurrentHashMap<>();
    private final Map<Long, OpenApiLogItem> openApiLogs = new ConcurrentHashMap<>();
    private final Map<String, GroupRuleItem> groupRules = new ConcurrentHashMap<>();
    private final Map<String, Long> userTokens = new ConcurrentHashMap<>();
    private final Map<String, AdminProfile> adminTokens = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> userTokenExpiresAt = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> adminTokenExpiresAt = new ConcurrentHashMap<>();
    private volatile SystemSettingItem systemSetting = new SystemSettingItem(
        "喜易云",
        "",
        "工作日 09:00-23:00 在线客服",
        "杭州云创蜗牛科技有限公司",
        "浙ICP备2024102496号",
        "浙ICP备2024102496号公安",
        "本站所有素材来源于网络 如有侵犯到您的知识产权或任何利益，请联系我们删除！",
        "MOCK",
        true,
        "TENCENT",
        false,
        30,
        true,
        false,
        true,
        "MOBILE",
        1L,
        Map.of("ops", "ops@example.com")
    );
    private final Set<String> viewedDeliveryOrders = ConcurrentHashMap.newKeySet();
    private final AtomicLong goodsId = new AtomicLong(10003);
    private final AtomicLong cardId = new AtomicLong(1);
    private final AtomicLong orderSeq = new AtomicLong(1);
    private final AtomicLong paymentSeq = new AtomicLong(1);
    private final AtomicLong paymentCallbackLogId = new AtomicLong(1);
    private final AtomicLong refundSeq = new AtomicLong(1);
    private final AtomicLong smsLogId = new AtomicLong(1);
    private final AtomicLong operationLogId = new AtomicLong(1);
    private final AtomicLong categoryId = new AtomicLong(40000);
    private final AtomicLong cardKindId = new AtomicLong(1);
    private final AtomicLong supplierId = new AtomicLong(20002);
    private final AtomicLong channelId = new AtomicLong(30002);
    private final AtomicLong productMonitorLogId = new AtomicLong(1);
    private final AtomicLong rechargeFieldId = new AtomicLong(7);
    private final AtomicLong userId = new AtomicLong(90003);
    private final AtomicLong openApiLogId = new AtomicLong(1);
    private final OrderRealtimeBroadcaster realtimeBroadcaster;
    private final PersistentOrderStore persistentOrderStore;
    private final CatalogPersistenceStore catalogPersistenceStore;
    private final AuditPersistenceStore auditPersistenceStore;
    private final ConfigPersistenceStore configPersistenceStore;
    private final String adminUsername;
    private final String adminPasswordBcrypt;
    private final String adminNickname;

    public InMemoryShopRepository(
        OrderRealtimeBroadcaster realtimeBroadcaster,
        @Value("${xiyiyun.admin.username:admin}") String adminUsername,
        @Value("${xiyiyun.admin.password-bcrypt:" + DEFAULT_ADMIN_PASSWORD_BCRYPT + "}") String adminPasswordBcrypt,
        @Value("${xiyiyun.admin.nickname:运营管理员}") String adminNickname
    ) {
        this(
            realtimeBroadcaster,
            (PersistentOrderStore) null,
            (CatalogPersistenceStore) null,
            (AuditPersistenceStore) null,
            (ConfigPersistenceStore) null,
            adminUsername,
            adminPasswordBcrypt,
            adminNickname
        );
    }

    @Autowired
    public InMemoryShopRepository(
        OrderRealtimeBroadcaster realtimeBroadcaster,
        ObjectProvider<PersistentOrderStore> persistentOrderStoreProvider,
        ObjectProvider<CatalogPersistenceStore> catalogPersistenceStoreProvider,
        ObjectProvider<AuditPersistenceStore> auditPersistenceStoreProvider,
        ObjectProvider<ConfigPersistenceStore> configPersistenceStoreProvider,
        @Value("${xiyiyun.admin.username:admin}") String adminUsername,
        @Value("${xiyiyun.admin.password-bcrypt:" + DEFAULT_ADMIN_PASSWORD_BCRYPT + "}") String adminPasswordBcrypt,
        @Value("${xiyiyun.admin.nickname:运营管理员}") String adminNickname
    ) {
        this(
            realtimeBroadcaster,
            persistentOrderStoreProvider.getIfAvailable(),
            catalogPersistenceStoreProvider.getIfAvailable(),
            auditPersistenceStoreProvider.getIfAvailable(),
            configPersistenceStoreProvider.getIfAvailable(),
            adminUsername,
            adminPasswordBcrypt,
            adminNickname
        );
    }

    private InMemoryShopRepository(
        OrderRealtimeBroadcaster realtimeBroadcaster,
        PersistentOrderStore persistentOrderStore,
        CatalogPersistenceStore catalogPersistenceStore,
        AuditPersistenceStore auditPersistenceStore,
        ConfigPersistenceStore configPersistenceStore,
        String adminUsername,
        String adminPasswordBcrypt,
        String adminNickname
    ) {
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.persistentOrderStore = persistentOrderStore;
        this.catalogPersistenceStore = catalogPersistenceStore;
        this.auditPersistenceStore = auditPersistenceStore;
        this.configPersistenceStore = configPersistenceStore;
        this.adminUsername = normalize(defaultText(adminUsername, "admin"));
        this.adminPasswordBcrypt = defaultText(adminPasswordBcrypt, DEFAULT_ADMIN_PASSWORD_BCRYPT);
        this.adminNickname = defaultText(adminNickname, "运营管理员");
        loadPersistentSystemSetting();
        seedCategories();
        seedRechargeFields();
        seedUserGroups();
        seedSuppliers();
        seedGoods();
        seedGoodsChannels();
        seedUsers();
        seedMemberCredentials();
        importCards(10001L, new CardImportRequest(List.of(
            "VIP-7D-ALPHA----8F2K",
            "VIP-7D-BRAVO----6P9Q",
            "VIP-7D-CHARLIE--3M7N",
            "VIP-7D-DELTA----1K5T"
        ), null));
        seedOrders();
    }

    public List<CategoryItem> listCategories() {
        Optional<List<CategoryItem>> persistent = persistentCategories();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return categories.values().stream()
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .map(this::enrichCategory)
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

    public synchronized CategoryItem createCategory(CreateCategoryRequest request) {
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
        CategoryItem item = new CategoryItem(
            id,
            request.name().trim(),
            defaultText(request.nickname(), ""),
            parentId,
            normalizeCategoryIcon(request.icon()),
            normalizeCategoryIcon(request.iconUrl()),
            normalizeCategoryIcon(request.customIconUrl()),
            request.sort() == null ? (int) (id % 1000) : request.sort(),
            categoryEnabled(request.enabled(), request.status()),
            categoryStatus(categoryEnabled(request.enabled(), request.status())),
            categoryLevel(parentId) + 1,
            false
        );
        categories.put(id, item);
        persistCategorySnapshot(item);
        return enrichCategory(item);
    }

    public synchronized CategoryItem updateCategory(Long id, UpdateCategoryRequest request) {
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

    public synchronized CategoryItem updateCategoryStatus(Long id, boolean enabled) {
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

    public synchronized void deleteCategory(Long id) {
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

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, boolean admin) {
        return listGoods(categoryId, search, platform, null, admin);
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, Long userGroupId, boolean admin) {
        Optional<List<GoodsItem>> persistent = persistentGoods();
        if (persistent.isPresent()) {
            return filterGoods(persistent.get(), categoryId, search, platform, userGroupId, admin, false);
        }
        return filterGoods(new ArrayList<>(goods.values()), categoryId, search, platform, userGroupId, admin, true);
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
        List<GroupRuleItem> activeRules = admin ? List.of() : rulesForGroup(userGroupId == null ? 1L : userGroupId);
        return source.stream()
            .filter(item -> admin || "ON_SALE".equals(item.status()))
            .filter(item -> categoryId == null || categoryScope.contains(item.categoryId()))
            .filter(item -> admin || !StringUtils.hasText(normalizedPlatform) || item.availablePlatforms().stream().map(this::normalize).anyMatch(normalizedPlatform::equals))
            .filter(item -> admin || allowedByGroupRules(item, activeRules))
            .filter(item -> !StringUtils.hasText(keyword) || containsKeyword(item, keyword))
            .map(item -> refresh ? refreshStock(item) : item)
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    public Optional<GoodsItem> findGoods(Long id) {
        return findGoodsSnapshot(id).map(this::refreshStock);
    }

    public SystemSettingItem systemSetting() {
        return systemSetting;
    }

    public synchronized SystemSettingItem updateSystemSetting(UpdateSystemSettingRequest request) {
        if (request == null) {
            return systemSetting;
        }
        systemSetting = new SystemSettingItem(
            defaultText(request.siteName(), systemSetting.siteName()),
            defaultText(request.logoUrl(), systemSetting.logoUrl()),
            defaultText(request.customerService(), systemSetting.customerService()),
            defaultText(request.companyName(), systemSetting.companyName()),
            defaultText(request.icpRecordNo(), systemSetting.icpRecordNo()),
            defaultText(request.policeRecordNo(), systemSetting.policeRecordNo()),
            defaultText(request.disclaimer(), systemSetting.disclaimer()),
            defaultText(request.paymentMode(), systemSetting.paymentMode()),
            request.autoRefundEnabled() == null ? systemSetting.autoRefundEnabled() : request.autoRefundEnabled(),
            defaultText(request.smsProvider(), systemSetting.smsProvider()),
            request.smsEnabled() == null ? systemSetting.smsEnabled() : request.smsEnabled(),
            Math.max(5, request.upstreamSyncSeconds() == null ? systemSetting.upstreamSyncSeconds() : request.upstreamSyncSeconds()),
            request.autoShelfEnabled() == null ? systemSetting.autoShelfEnabled() : request.autoShelfEnabled(),
            request.autoPriceEnabled() == null ? systemSetting.autoPriceEnabled() : request.autoPriceEnabled(),
            request.registrationEnabled() == null ? systemSetting.registrationEnabled() : request.registrationEnabled(),
            normalizeRegistrationType(defaultText(request.registrationType(), systemSetting.registrationType())),
            validDefaultUserGroupId(request.defaultUserGroupId() == null ? systemSetting.defaultUserGroupId() : request.defaultUserGroupId()),
            request.notificationReceivers() == null ? systemSetting.notificationReceivers() : Map.copyOf(request.notificationReceivers())
        );
        persistSystemSetting(systemSetting);
        return systemSetting;
    }

    public synchronized AuthSession<UserItem> loginUser(LoginRequest request) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("account is required");
        }
        UserItem user = allUserSnapshots().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))
            .findFirst()
            .orElseGet(() -> createUserFromAccount(account, request == null ? "" : request.code()));
        UserItem next = withUserLastLoginAt(user, OffsetDateTime.now());
        users.put(next.id(), next);
        persistUserSnapshot(next);
        String token = "h5_" + UUID.randomUUID();
        userTokens.put(token, next.id());
        userTokenExpiresAt.put(token, OffsetDateTime.now().plus(USER_TOKEN_TTL));
        return new AuthSession<>(token, withGroupName(next));
    }

    public Optional<UserItem> findUserByToken(String token) {
        String cleanToken = cleanBearerToken(token);
        if (isTokenExpired(cleanToken, userTokenExpiresAt)) {
            userTokens.remove(cleanToken);
            userTokenExpiresAt.remove(cleanToken);
            return Optional.empty();
        }
        Long userId = userTokens.get(cleanToken);
        return userId == null ? Optional.empty() : findUserSnapshot(userId).map(this::withGroupName);
    }

    public AuthSession<AdminProfile> loginAdmin(LoginRequest request) {
        String account = normalize(request == null ? "" : request.account());
        String password = request == null ? "" : request.password();
        if (!Objects.equals(adminUsername, account) || !ADMIN_PASSWORD_ENCODER.matches(password, adminPasswordBcrypt)) {
            throw new IllegalArgumentException("admin account or password is invalid");
        }
        AdminProfile profile = new AdminProfile(
            1L,
            adminUsername,
            adminNickname,
            List.of("dashboard:read", "goods:manage", "orders:manage", "users:manage", "settings:manage")
        );
        String token = "admin_" + UUID.randomUUID();
        adminTokens.put(token, profile);
        adminTokenExpiresAt.put(token, OffsetDateTime.now().plus(ADMIN_TOKEN_TTL));
        return new AuthSession<>(token, profile);
    }

    public Optional<AdminProfile> findAdminByToken(String token) {
        String cleanToken = cleanBearerToken(token);
        if (isTokenExpired(cleanToken, adminTokenExpiresAt)) {
            adminTokens.remove(cleanToken);
            adminTokenExpiresAt.remove(cleanToken);
            return Optional.empty();
        }
        return Optional.ofNullable(adminTokens.get(cleanToken));
    }

    private boolean isTokenExpired(String token, Map<String, OffsetDateTime> expiresAtByToken) {
        OffsetDateTime expiresAt = expiresAtByToken.get(token);
        return expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now());
    }

    public List<UserGroupItem> listUserGroups() {
        Optional<List<UserGroupItem>> persistent = persistentUserGroups();
        if (persistent.isPresent()) {
            return persistent.get().stream()
                .map(group -> new UserGroupItem(
                    group.id(),
                    group.name(),
                    group.description(),
                    group.defaultGroup(),
                    (int) users.values().stream().filter(user -> Objects.equals(user.groupId(), group.id())).count(),
                    group.status(),
                    group.orderEnabled(),
                    group.realNameRequiredForOrder(),
                    enrichRuleNames(group.rules())
                ))
                .sorted(Comparator.comparing(UserGroupItem::id))
                .toList();
        }
        return userGroups.values().stream()
            .map(group -> new UserGroupItem(
                group.id(),
                group.name(),
                group.description(),
                group.defaultGroup(),
                (int) users.values().stream().filter(user -> Objects.equals(user.groupId(), group.id())).count(),
                group.status(),
                group.orderEnabled(),
                group.realNameRequiredForOrder(),
                rulesForGroup(group.id())
            ))
            .sorted(Comparator.comparing(UserGroupItem::id))
            .toList();
    }

    public List<UserItem> listUsers() {
        Optional<List<UserItem>> persistent = persistentUsers();
        if (persistent.isPresent()) {
            return persistent.get().stream()
                .map(this::withGroupName)
                .sorted(Comparator.comparing(UserItem::id))
                .toList();
        }
        return users.values().stream()
            .map(this::withGroupName)
            .sorted(Comparator.comparing(UserItem::id))
            .toList();
    }

    public synchronized UserGroupItem createUserGroup(CreateUserGroupRequest request) {
        String name = request == null ? "" : defaultText(request.name(), "").trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("group name is required");
        }
        if (userGroupNameExists(name, null)) {
            throw new IllegalStateException("group name already exists");
        }
        Long id = maxUserGroupId() + 1;
        UserGroupItem item = new UserGroupItem(
            id,
            name,
            defaultText(request.description(), "自定义会员等级"),
            Boolean.TRUE.equals(request.defaultGroup()),
            0,
            defaultText(request.status(), "ENABLED"),
            request.orderEnabled() == null || Boolean.TRUE.equals(request.orderEnabled()),
            Boolean.TRUE.equals(request.realNameRequiredForOrder()),
            List.of()
        );
        userGroups.put(id, item);
        persistUserGroup(item);
        return item;
    }

    public synchronized UserGroupItem updateUserGroupOrderPermission(Long groupId, UpdateUserGroupOrderPermissionRequest request) {
        UserGroupItem current = findUserGroupSnapshot(groupId).orElse(null);
        if (current == null) {
            throw new IllegalArgumentException("user group not found");
        }
        UserGroupItem next = new UserGroupItem(
            current.id(),
            current.name(),
            current.description(),
            current.defaultGroup(),
            current.userCount(),
            current.status(),
            request == null || request.orderEnabled() == null ? current.orderEnabled() : request.orderEnabled(),
            request == null || request.realNameRequiredForOrder() == null ? current.realNameRequiredForOrder() : request.realNameRequiredForOrder(),
            rulesForGroup(current.id())
        );
        userGroups.put(groupId, next);
        persistUserGroup(next);
        return next;
    }

    public synchronized List<GroupRuleItem> updateGroupRules(Long groupId, UpdateGroupRulesRequest request) {
        if (findUserGroupSnapshot(groupId).isEmpty()) {
            throw new IllegalArgumentException("user group not found");
        }
        String ruleType = normalizeRuleType(request == null ? "" : request.ruleType());
        if (!"CATEGORY".equals(ruleType) && !"PLATFORM".equals(ruleType)) {
            throw new IllegalArgumentException("ruleType must be CATEGORY or PLATFORM");
        }

        groupRules.keySet().removeIf(key -> key.startsWith(groupId + ":" + ruleType + ":"));
        if (request != null && request.rules() != null) {
            for (GroupRulePatch patch : request.rules()) {
                String permission = normalizePermission(patch.permission());
                if ("NONE".equals(permission)) {
                    continue;
                }
                GroupRuleItem item = createRule(groupId, ruleType, patch, permission);
                groupRules.put(ruleKey(item), item);
            }
        }
        List<GroupRuleItem> rules = groupRules.values().stream()
            .filter(rule -> Objects.equals(rule.groupId(), groupId))
            .filter(rule -> Objects.equals(rule.ruleType(), ruleType))
            .sorted(Comparator.comparing(GroupRuleItem::ruleType)
                .thenComparing(rule -> defaultText(rule.targetName(), rule.targetCode())))
            .toList();
        persistGroupRules(groupId, ruleType, rules);
        return rulesForGroup(groupId);
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
            throw new IllegalArgumentException("field code is required");
        }
        if (rechargeFieldCodeExists(code, null)) {
            throw new IllegalStateException("field code already exists");
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
        if (rechargeFieldCodeExists(code, id)) {
            throw new IllegalStateException("field code already exists");
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

    public synchronized UserItem updateUserGroup(Long userId, UpdateUserGroupRequest request) {
        UserItem user = findUserSnapshot(userId).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        Long groupId = request == null || request.groupId() == null ? 1L : request.groupId();
        if (findUserGroupSnapshot(groupId).isEmpty()) {
            throw new IllegalArgumentException("user group not found");
        }
        UserItem next = new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            groupId,
            groupName(groupId),
            user.balance(),
            user.deposit(),
            user.status(),
            user.createdAt(),
            user.lastLoginAt(),
            user.realNameType(),
            user.realName(),
            user.subjectName(),
            user.certificateNo(),
            user.verificationStatus()
        );
        users.put(userId, next);
        persistUserSnapshot(next);
        return next;
    }

    public synchronized UserItem adjustUserFunds(Long userId, UserFundAdjustRequest request) {
        UserItem user = findUserSnapshot(userId).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        BigDecimal amount = request == null || request.amount() == null ? BigDecimal.ZERO : request.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }

        String accountType = normalize(request.accountType());
        String direction = normalize(request.direction());
        if (!List.of("balance", "deposit").contains(accountType)) {
            throw new IllegalArgumentException("accountType must be balance or deposit");
        }
        if (!List.of("increase", "decrease").contains(direction)) {
            throw new IllegalArgumentException("direction must be increase or decrease");
        }

        BigDecimal currentBalance = user.balance() == null ? BigDecimal.ZERO : user.balance();
        BigDecimal currentDeposit = user.deposit() == null ? BigDecimal.ZERO : user.deposit();
        BigDecimal nextBalance = currentBalance;
        BigDecimal nextDeposit = currentDeposit;
        if ("balance".equals(accountType)) {
            nextBalance = adjustFundValue(currentBalance, amount, direction, "余额");
        } else {
            nextDeposit = adjustFundValue(currentDeposit, amount, direction, "保证金");
        }

        UserItem next = new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            nextBalance,
            nextDeposit,
            user.status(),
            user.createdAt(),
            user.lastLoginAt(),
            user.realNameType(),
            user.realName(),
            user.subjectName(),
            user.certificateNo(),
            user.verificationStatus()
        );
        users.put(userId, next);
        persistUserSnapshot(next);
        appendOperation(
            "USER_FUND_ADJUST",
            "USER",
            String.valueOf(userId),
            accountType + ":" + direction + ":" + amount + ":" + defaultText(request.remark(), "")
        );
        return next;
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

    public synchronized GoodsChannelItem createGoodsChannel(Long targetGoodsId, CreateGoodsChannelRequest request) {
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

    public synchronized void deleteGoodsChannel(Long targetGoodsId, Long targetChannelId) {
        GoodsChannelItem item = findGoodsChannelSnapshot(targetChannelId).orElse(null);
        if (item == null || !Objects.equals(item.goodsId(), targetGoodsId)) {
            throw new IllegalArgumentException("channel not found");
        }
        goodsChannels.remove(targetChannelId);
        productMonitorStates.remove(targetChannelId);
        deletePersistentGoodsChannel(targetChannelId);
    }

    public ProductMonitorOverview productMonitorOverview() {
        return new ProductMonitorOverview(listProductMonitorItems(), listProductMonitorLogs());
    }

    public List<ProductMonitorItem> listProductMonitorItems() {
        OffsetDateTime now = OffsetDateTime.now();
        return goodsChannels.values().stream()
            .filter(this::isProductMonitorChannel)
            .sorted(Comparator.comparing(GoodsChannelItem::supplierName).thenComparing(GoodsChannelItem::supplierGoodsId))
            .map(channel -> productMonitorItem(channel, ensureProductMonitorState(channel.id(), now)))
            .toList();
    }

    public List<ProductMonitorLogItem> listProductMonitorLogs() {
        return productMonitorLogs.values().stream()
            .sorted(Comparator.comparing(ProductMonitorLogItem::id).reversed())
            .limit(200)
            .toList();
    }

    public List<Long> dueProductMonitorChannelIds(OffsetDateTime now) {
        return goodsChannels.values().stream()
            .filter(this::isProductMonitorChannel)
            .filter(channel -> {
                ProductMonitorState state = ensureProductMonitorState(channel.id(), now);
                return !state.scanning() && (state.nextScanAt() == null || !state.nextScanAt().isAfter(now));
            })
            .map(GoodsChannelItem::id)
            .sorted()
            .toList();
    }

    public synchronized List<ProductMonitorScanResult> scanAllProductMonitorChannels(boolean manual) {
        return goodsChannels.values().stream()
            .filter(this::isProductMonitorChannel)
            .sorted(Comparator.comparing(GoodsChannelItem::id))
            .map(channel -> scanProductMonitorChannel(channel.id(), manual))
            .filter(Objects::nonNull)
            .toList();
    }

    public synchronized ProductMonitorScanResult scanProductMonitorChannel(Long channelId, boolean manual) {
        GoodsChannelItem channel = goodsChannels.get(channelId);
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (channel == null || !isProductMonitorChannel(channel)) {
            return null;
        }

        productMonitorStates.put(channelId, ensureProductMonitorState(channelId, startedAt).start(startedAt));

        GoodsItem current = findGoodsSnapshot(channel.goodsId()).orElse(null);
        SupplierItem supplier = suppliers.get(channel.supplierId());
        List<String> changes = new ArrayList<>();
        String result = "NO_CHANGE";
        String message = "本轮扫描无变动";
        boolean changed = false;

        try {
            if (current == null) {
                throw new IllegalStateException("本地商品不存在");
            }
            if (supplier == null) {
                throw new IllegalStateException("供应商不存在");
            }
            if (!"ENABLED".equals(supplier.status())) {
                throw new IllegalStateException("供应商已停用");
            }
            if (!"ENABLED".equals(channel.status())) {
                throw new IllegalStateException("渠道已停用");
            }

            boolean primaryChannel = isPrimaryProductMonitorChannel(channel);
            MonitoredRemoteGoods remote = monitoredRemoteGoods(current, channel, supplier, ensureProductMonitorState(channelId, startedAt).scanCount() + 1, manual);
            GoodsItem next = applyMonitoredRemoteGoods(current, remote, changes);
            changed = !changes.isEmpty();
            if (changed && primaryChannel) {
                goods.put(current.id(), next);
                persistGoodsSnapshot(next);
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
        OffsetDateTime nextScanAt = finishedAt.plusSeconds(60);
        ProductMonitorState state = ensureProductMonitorState(channelId, finishedAt).finish(finishedAt, nextScanAt, result, message, changed);
        productMonitorStates.put(channelId, state);

        ProductMonitorLogItem log = new ProductMonitorLogItem(
            productMonitorLogId.getAndIncrement(),
            channel.id(),
            channel.goodsId(),
            findGoodsSnapshot(channel.goodsId()).map(GoodsItem::goodsName).orElse("-"),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            result,
            message,
            List.copyOf(changes),
            finishedAt,
            nextScanAt
        );
        productMonitorLogs.put(log.id(), log);
        trimProductMonitorLogs();
        realtimeBroadcaster.publishProductMonitorLog(log);
        return new ProductMonitorScanResult(productMonitorItem(channel, state), log);
    }

    public List<SupplierItem> listSuppliers() {
        Optional<List<SupplierItem>> persistent = persistentSuppliers();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return suppliers.values().stream()
            .sorted(Comparator.comparing(SupplierItem::id))
            .toList();
    }

    public synchronized SupplierItem createSupplier(CreateSupplierRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("supplier name is required");
        }
        String name = request.name().trim();
        if (supplierNameExists(name, null)) {
            throw new IllegalStateException("supplier name already exists");
        }
        Long id = allocateIncrementingId(supplierId, maxSupplierId());
        String appKey = defaultText(request.appKey(), defaultText(request.appId(), "demo-app-key"));
        String appSecret = defaultText(request.appSecret(), "demo-secret");
        String apiKey = defaultText(request.apiKey(), appSecret);
        String apiKeyMasked = StringUtils.hasText(request.apiKeyMasked()) ? request.apiKeyMasked().trim() : mask(apiKey);
        String platformType = defaultText(request.platformType(), "CUSTOM");
        String appId = firstText(request.appId(), request.userId(), appKey);
        String userId = firstText(request.userId(), appId, appKey);
        SupplierItem item = new SupplierItem(
            id,
            name,
            platformType,
            defaultText(request.baseUrl(), "https://supplier.example.com/api"),
            appKey,
            mask(appSecret),
            userId,
            appId,
            apiKeyMasked,
            normalizedCallbackUrl(request.callbackUrl()),
            normalizedTimeoutSeconds(request.timeoutSeconds()),
            request.balance() == null ? BigDecimal.ZERO : request.balance(),
            defaultText(request.status(), "ENABLED"),
            defaultText(request.remark(), ""),
            OffsetDateTime.now()
        );
        suppliers.put(id, item);
        if (StringUtils.hasText(apiKey)) {
            supplierApiKeys.put(id, apiKey);
        }
        persistSupplier(item);
        return item;
    }

    public synchronized SupplierItem updateSupplier(Long id, CreateSupplierRequest request) {
        SupplierItem current = requiredSupplier(id);
        if (request == null) {
            return current;
        }
        String name = defaultText(request.name(), current.name()).trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("supplier name is required");
        }
        if (supplierNameExists(name, id)) {
            throw new IllegalStateException("supplier name already exists");
        }
        String platformType = defaultText(request.platformType(), current.platformType());
        String appKey = defaultText(request.appKey(), current.appKey());
        String appId = defaultText(request.appId(), current.appId());
        String userId = isKasushouPlatform(platformType)
            ? firstText(request.userId(), appId, defaultText(current.userId(), appKey))
            : defaultText(request.userId(), current.userId());
        String appSecretMasked = current.appSecretMasked();
        if (StringUtils.hasText(request.appSecret())) {
            appSecretMasked = mask(request.appSecret());
        }
        String apiKeyMasked = current.apiKeyMasked();
        if (StringUtils.hasText(request.apiKey())) {
            String apiKey = request.apiKey().trim();
            supplierApiKeys.put(id, apiKey);
            apiKeyMasked = mask(apiKey);
        } else if (StringUtils.hasText(request.appSecret())) {
            String apiKey = request.appSecret().trim();
            supplierApiKeys.put(id, apiKey);
            apiKeyMasked = mask(apiKey);
        } else if (StringUtils.hasText(request.apiKeyMasked())) {
            apiKeyMasked = request.apiKeyMasked().trim();
        }
        SupplierItem next = new SupplierItem(
            current.id(),
            name,
            platformType,
            defaultText(request.baseUrl(), current.baseUrl()),
            appKey,
            appSecretMasked,
            userId,
            appId,
            apiKeyMasked,
            request.callbackUrl() == null ? current.callbackUrl() : normalizedCallbackUrl(request.callbackUrl()),
            request.timeoutSeconds() == null ? current.timeoutSeconds() : normalizedTimeoutSeconds(request.timeoutSeconds()),
            request.balance() == null ? current.balance() : request.balance(),
            defaultText(request.status(), current.status()),
            defaultText(request.remark(), current.remark()),
            current.lastSyncAt()
        );
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized void deleteSupplier(Long id) {
        requiredSupplier(id);
        suppliers.remove(id);
        supplierApiKeys.remove(id);
        remoteGoodsSyncResults.remove(id);
        goodsChannels.entrySet().removeIf(entry -> Objects.equals(entry.getValue().supplierId(), id));
        deletePersistentSupplier(id);
    }

    public synchronized SupplierItem updateSupplierStatus(Long id, boolean enabled) {
        SupplierItem item = requiredSupplier(id);
        SupplierItem next = item.withStatus(enabled ? "ENABLED" : "DISABLED");
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized SupplierItem refreshSupplierBalance(Long id) {
        SupplierItem item = requiredSupplier(id);
        SupplierItem next = isKasushouSupplier(item)
            ? refreshKasushouBalance(item)
            : item.withBalance(item.balance().add(BigDecimal.valueOf(12.34)));
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized SupplierItem testSupplierConnection(Long id) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        SupplierItem next = isKasushouSupplier(item) ? testKasushouConnection(item) : item.withBalance(item.balance());
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized RemoteGoodsSyncResult syncRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        if (!isKasushouSupplier(item)) {
            throw new IllegalArgumentException("supplier platformType must be KASUSHOU_2");
        }

        int page = request == null || request.page() == null ? 1 : Math.max(1, request.page());
        int limit = request == null || request.limit() == null ? 20 : Math.max(1, Math.min(request.limit(), 100));
        Long cateId = request == null ? null : request.cateId();
        String keyword = request == null ? "" : defaultText(request.keyword(), "").trim();

        RemoteGoodsSyncResult result = isPlaceholderBaseUrl(item.baseUrl())
            ? mockKasushouGoodsSyncResult(item.id(), cateId, keyword, page, limit)
            : fetchKasushouGoods(item, cateId, keyword, page, limit);
        remoteGoodsSyncResults.put(id, result);
        SupplierItem synced = item.withLastSyncAt(result.syncedAt());
        suppliers.put(id, synced);
        persistSupplier(synced);
        return result;
    }

    public Optional<RemoteGoodsSyncResult> latestRemoteGoods(Long id) {
        requiredSupplier(id);
        return Optional.ofNullable(remoteGoodsSyncResults.get(id));
    }

    public synchronized RemoteGoodsSyncResult sourceConnectRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }

        int page = request == null || request.page() == null ? 1 : Math.max(1, request.page());
        int limit = request == null || request.limit() == null ? 20 : Math.max(1, Math.min(request.limit(), 100));
        Long cateId = request == null ? null : request.cateId();
        String keyword = request == null ? "" : defaultText(request.keyword(), "").trim();

        RemoteGoodsSyncResult result = isKasushouSupplier(item)
            ? syncRemoteGoods(id, request)
            : mockKasushouGoodsSyncResult(item.id(), cateId, keyword, page, limit);
        remoteGoodsSyncResults.put(id, result);
        SupplierItem synced = item.withLastSyncAt(result.syncedAt());
        suppliers.put(id, synced);
        persistSupplier(synced);
        return result;
    }

    public synchronized SourceCloneResult cloneSourceGoods(Long supplierId, SourceCloneRequest request) {
        SupplierItem supplier = requiredSupplier(supplierId);
        if (!"ENABLED".equals(supplier.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        List<SourceCloneConfigItem> cloneItems = sourceCloneItems(request);
        if (cloneItems.isEmpty()) {
            throw new IllegalArgumentException("items is required");
        }

        RemoteGoodsSyncResult source = Optional.ofNullable(remoteGoodsSyncResults.get(supplierId))
            .orElseGet(() -> sourceConnectRemoteGoods(supplierId, new SyncGoodsRequest(null, "", 1, 100)));
        Map<String, RemoteGoodsItem> remoteGoodsById = source.items().stream()
            .filter(item -> StringUtils.hasText(item.supplierGoodsId()))
            .collect(java.util.stream.Collectors.toMap(item -> item.supplierGoodsId().trim(), item -> item, (left, right) -> left));

        int createdCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        List<SourceCloneItem> results = new ArrayList<>();
        for (SourceCloneConfigItem cloneItem : cloneItems) {
            String normalizedId = defaultText(cloneItem.supplierGoodsId(), "").trim();
            if (!StringUtils.hasText(normalizedId)) {
                failedCount++;
                results.add(new SourceCloneItem("", "", "FAILED", null, null, "上游商品ID为空"));
                continue;
            }

            Optional<GoodsChannelItem> existing = goodsChannels.values().stream()
                .filter(channel -> Objects.equals(channel.supplierId(), supplierId))
                .filter(channel -> Objects.equals(channel.supplierGoodsId(), normalizedId))
                .findFirst();
            if (existing.isPresent()) {
                skippedCount++;
                GoodsChannelItem channel = existing.get();
                GoodsItem item = findGoodsSnapshot(channel.goodsId()).orElse(null);
                results.add(new SourceCloneItem(
                    normalizedId,
                    item == null ? normalizedId : item.goodsName(),
                    "SKIPPED",
                    channel.goodsId(),
                    channel.id(),
                    "已存在对接关系，已跳过"
                ));
                continue;
            }

            RemoteGoodsItem remote = remoteGoodsById.get(normalizedId);
            if (remote == null) {
                failedCount++;
                results.add(new SourceCloneItem(normalizedId, normalizedId, "FAILED", null, null, "未找到上游商品"));
                continue;
            }

            try {
                String goodsName = firstText(cloneItem.name(), remote.goodsName(), normalizedId);
                BigDecimal price = cloneItem.price() == null ? defaultDecimal(remote.goodsPrice()) : cloneItem.price();
                BigDecimal originalPrice = cloneItem.originalPrice() == null ? remote.faceValue() : cloneItem.originalPrice();
                int stock = cloneItem.stock() == null ? Math.max(0, remote.stockNum() == null ? 0 : remote.stockNum()) : Math.max(0, cloneItem.stock());
                String status = StringUtils.hasText(cloneItem.status()) ? cloneItem.status() : remoteGoodsStatus(remote);
                int priority = cloneItem.priority() == null ? normalizedPriority(request.priority()) : normalizedPriority(cloneItem.priority());
                int timeoutSeconds = cloneItem.timeoutSeconds() == null
                    ? normalizedChannelTimeout(request.timeoutSeconds())
                    : normalizedChannelTimeout(cloneItem.timeoutSeconds());
                GoodsItem created = createGoods(new CreateGoodsRequest(
                    cloneItem.categoryId() == null ? request.categoryId() : cloneItem.categoryId(),
                    goodsName,
                    goodsName,
                    "由 " + supplier.name() + " 一键对接创建",
                    defaultText(cloneItem.description(), "货源对接自动创建，已绑定上游商品 " + normalizedId),
                    List.of(),
                    defaultText(cloneItem.coverUrl(), ""),
                    List.of(),
                    List.of(),
                    List.of(new GoodsIntegrationItem(
                        String.valueOf(supplier.id()),
                        supplier.platformType(),
                        normalizedId,
                        goodsName,
                        price,
                        remote.status(),
                        remote.stockNum(),
                        goodsName,
                        OffsetDateTime.now().toString(),
                        true
                    )),
                    false,
                    true,
                    GoodsType.DIRECT,
                    "GENERAL",
                    price,
                    originalPrice,
                    1,
                    Boolean.TRUE.equals(cloneItem.requireRechargeAccount()),
                    cloneItem.accountTypes(),
                    "follow-upstream",
                    "FIXED",
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    stock,
                    status,
                    List.of("api-source"),
                    List.of("private"),
                    List.of(),
                    null
                ));
                GoodsChannelItem channel = createGoodsChannel(created.id(), new CreateGoodsChannelRequest(
                    supplierId,
                    normalizedId,
                    priority,
                    timeoutSeconds,
                    "ENABLED"
                ));
                createdCount++;
                results.add(new SourceCloneItem(normalizedId, goodsName, "CREATED", created.id(), channel.id(), "已创建本地商品并绑定货源"));
            } catch (RuntimeException ex) {
                failedCount++;
                results.add(new SourceCloneItem(normalizedId, remote.goodsName(), "FAILED", null, null, ex.getMessage()));
            }
        }

        return new SourceCloneResult(createdCount, skippedCount, failedCount, List.copyOf(results));
    }

    private List<SourceCloneConfigItem> sourceCloneItems(SourceCloneRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.items() != null && !request.items().isEmpty()) {
            return request.items();
        }
        if (request.supplierGoodsIds() == null || request.supplierGoodsIds().isEmpty()) {
            return List.of();
        }
        return request.supplierGoodsIds().stream()
            .map(id -> new SourceCloneConfigItem(
                id,
                null,
                request.categoryId(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                false,
                request.priority(),
                request.timeoutSeconds()
            ))
            .toList();
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int normalizedPriority(Integer value) {
        return value == null ? 10 : Math.max(1, value);
    }

    private int normalizedChannelTimeout(Integer value) {
        return value == null ? 30 : Math.max(5, value);
    }

    private String remoteGoodsStatus(RemoteGoodsItem remote) {
        return remote.canBuy() == Boolean.FALSE || remote.canNoBuy() == Boolean.TRUE ? "OFF_SALE" : "ON_SALE";
    }

    public List<OrderItem> listOrders() {
        return listOrders(null, null, null);
    }

    public List<OrderItem> listOrdersForUser(Long userId) {
        expireStaleUnpaidOrders();
        Optional<List<OrderItem>> persistent = persistentOrders();
        return (persistent.orElseGet(() -> new ArrayList<>(orders.values()))).stream()
            .filter(order -> Objects.equals(order.userId(), userId))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
    }

    public List<OrderItem> listOrders(String search, String status, String goodsType) {
        expireStaleUnpaidOrders();
        String keyword = normalize(search);
        String normalizedStatus = normalize(status);
        String normalizedGoodsType = normalize(goodsType);

        Optional<List<OrderItem>> persistent = persistentOrders();
        return (persistent.orElseGet(() -> new ArrayList<>(orders.values()))).stream()
            .filter(order -> !StringUtils.hasText(keyword) || containsOrderKeyword(order, keyword))
            .filter(order -> !StringUtils.hasText(normalizedStatus) || normalize(String.valueOf(order.status())).equals(normalizedStatus))
            .filter(order -> !StringUtils.hasText(normalizedGoodsType) || normalize(String.valueOf(order.goodsType())).equals(normalizedGoodsType))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
    }

    public Optional<OrderItem> findOrder(String orderNo) {
        Optional<OrderItem> persistent = persistentOrder(orderNo);
        if (persistent.isPresent()) {
            return persistent;
        }
        OrderItem order = orders.get(orderNo);
        return Optional.ofNullable(order == null ? null : expireOrderIfNeeded(order, OffsetDateTime.now()));
    }

    public Optional<OrderItem> findOrderForUser(String orderNo, Long userId) {
        return findOrder(orderNo).filter(order -> Objects.equals(order.userId(), userId));
    }

    public Optional<PaymentItem> findPaymentForUser(String paymentNo, Long userId) {
        PaymentItem payment = payments.get(paymentNo);
        return Optional.ofNullable(payment == null || !Objects.equals(payment.userId(), userId) ? null : payment);
    }

    public List<PaymentItem> listPayments() {
        Optional<List<PaymentItem>> persistent = persistentPayments();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return payments.values().stream()
            .sorted(Comparator.comparing(PaymentItem::createdAt).reversed())
            .toList();
    }

    public List<PaymentCallbackLogItem> listPaymentCallbackLogs() {
        Optional<List<PaymentCallbackLogItem>> persistent = persistentPaymentCallbackLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return paymentCallbackLogs.values().stream()
            .sorted(Comparator.comparing(PaymentCallbackLogItem::createdAt).reversed())
            .toList();
    }

    public List<RefundItem> listRefunds() {
        Optional<List<RefundItem>> persistent = persistentRefunds();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return refunds.values().stream()
            .sorted(Comparator.comparing(RefundItem::createdAt).reversed())
            .toList();
    }

    public List<SmsLogItem> listSmsLogs() {
        Optional<List<SmsLogItem>> persistent = persistentSmsLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return smsLogs.values().stream()
            .sorted(Comparator.comparing(SmsLogItem::createdAt).reversed())
            .toList();
    }

    public List<OperationLogItem> listOperationLogs() {
        Optional<List<OperationLogItem>> persistent = persistentOperationLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return operationLogs.values().stream()
            .sorted(Comparator.comparing(OperationLogItem::createdAt).reversed())
            .toList();
    }

    public List<OpenApiLogItem> listOpenApiLogs() {
        Optional<List<OpenApiLogItem>> persistent = persistentOpenApiLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return openApiLogs.values().stream()
            .sorted(Comparator.comparing(OpenApiLogItem::createdAt).reversed())
            .toList();
    }

    public List<MemberApiCredentialItem> listMemberCredentials() {
        return memberCredentials.values().stream()
            .sorted(Comparator.comparing(MemberApiCredentialItem::id))
            .toList();
    }

    public synchronized MemberApiCredentialItem memberCredentialForUser(Long userId) {
        if (findUserSnapshot(userId).isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        return memberCredentials.values().stream()
            .filter(item -> Objects.equals(item.userId(), userId))
            .findFirst()
            .orElseGet(() -> createDefaultMemberCredential(userId));
    }

    public synchronized MemberApiCredentialItem saveMemberCredential(Long userId, MemberApiCredentialRequest request) {
        if (findUserSnapshot(userId).isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        MemberApiCredentialItem current = memberCredentialForUser(userId);
        String appKey = defaultText(request == null ? "" : request.appKey(), current.appKey()).trim();
        if (!StringUtils.hasText(appKey)) {
            appKey = memberAppKey(userId);
        }
        MemberApiCredentialItem duplicate = memberCredentials.get(appKey);
        if (duplicate != null && !Objects.equals(duplicate.userId(), userId)) {
            throw new IllegalStateException("app key already exists");
        }
        String secret = request != null && Boolean.TRUE.equals(request.resetSecret())
            ? memberAppSecret()
            : defaultText(request == null ? "" : request.appSecret(), current.appSecret());
        String status = request == null || request.enabled() == null
            ? current.status()
            : Boolean.TRUE.equals(request.enabled()) ? "ENABLED" : "DISABLED";
        List<String> ipWhitelist = request == null || request.ipWhitelist() == null
            ? current.ipWhitelist()
            : normalizeTextList(request.ipWhitelist());
        int dailyLimit = request == null || request.dailyLimit() == null
            ? current.dailyLimit()
            : Math.max(1, request.dailyLimit());
        MemberApiCredentialItem next = new MemberApiCredentialItem(
            current.id(),
            userId,
            appKey,
            secret,
            status,
            ipWhitelist,
            dailyLimit,
            current.createdAt(),
            current.lastUsedAt()
        );
        if (!Objects.equals(current.appKey(), appKey)) {
            memberCredentials.remove(current.appKey());
        }
        memberCredentials.put(appKey, next);
        return next;
    }

    public UserItem authenticateMemberApi(String appKey, String timestamp, String nonce, String signature, String path, String clientIp) {
        MemberApiCredentialItem credential = memberCredentials.get(appKey);
        if (credential == null || !"ENABLED".equals(credential.status())) {
            appendOpenApiLog(null, appKey, path, "FAILED", "invalid app key");
            throw new IllegalArgumentException("invalid app key");
        }
        if (!isMemberApiIpAllowed(credential, clientIp)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "ip not allowed");
            throw new IllegalArgumentException("ip not allowed");
        }
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "missing signature headers");
            throw new IllegalArgumentException("missing signature headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "invalid timestamp");
            throw new IllegalArgumentException("invalid timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > 300) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "timestamp expired");
            throw new IllegalArgumentException("timestamp expired");
        }
        String nonceKey = appKey + ":" + nonce;
        if (isMemberNonceReplay(nonceKey, OffsetDateTime.now())) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "nonce replay");
            throw new IllegalArgumentException("nonce replay");
        }
        String payload = timestamp + "\n" + nonce + "\n" + path;
        if (!constantTimeEquals(hmacSha256(credential.appSecret(), payload), signature)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "invalid signature");
            throw new IllegalArgumentException("invalid signature");
        }
        UserItem user = findUserSnapshot(credential.userId()).orElse(null);
        if (user == null) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "user not found");
            throw new IllegalArgumentException("user not found");
        }
        memberCredentials.put(appKey, new MemberApiCredentialItem(
            credential.id(),
            credential.userId(),
            credential.appKey(),
            credential.appSecret(),
            credential.status(),
            credential.ipWhitelist(),
            credential.dailyLimit(),
            credential.createdAt(),
            OffsetDateTime.now()
        ));
        appendOpenApiLog(user.id(), appKey, path, "SUCCESS", "ok");
        return withGroupName(user);
    }

    private boolean isMemberNonceReplay(String nonceKey, OffsetDateTime now) {
        cleanupExpiredMemberNonces(now);
        OffsetDateTime existing = memberNonceExpiresAt.putIfAbsent(nonceKey, now.plus(MEMBER_API_NONCE_TTL));
        return existing != null && existing.isAfter(now);
    }

    private void cleanupExpiredMemberNonces(OffsetDateTime now) {
        memberNonceExpiresAt.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    public synchronized OrderItem createMemberOrder(CreateOrderRequest request, Long userId) {
        OrderItem order = createOrder(request, userId);
        UserItem user = requiredUser(userId);
        if (user.balance().compareTo(order.payAmount()) < 0) {
            orders.remove(order.orderNo());
            throw new IllegalStateException("balance is insufficient");
        }
        UserItem debited = new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            user.balance().subtract(order.payAmount()),
            user.deposit(),
            user.status(),
            user.createdAt(),
            user.lastLoginAt(),
            user.realNameType(),
            user.realName(),
            user.subjectName(),
            user.certificateNo(),
            user.verificationStatus()
        );
        users.put(userId, debited);
        persistUserSnapshot(debited);
        return payOrder(order.orderNo(), userId, new PayOrderRequest("balance", "member-api"));
    }

    public synchronized OrderItem handlePaymentCallback(String provider, PaymentCallbackRequest request) {
        String paymentNo = request == null ? "" : defaultText(request.paymentNo(), "");
        PaymentItem payment = payments.get(paymentNo);
        if (payment == null) {
            recordPaymentCallback(provider, request, "FAILED", "payment not found");
            throw new IllegalArgumentException("payment not found");
        }
        try {
            ensurePaymentCallbackMatches(payment, request);
        } catch (IllegalArgumentException ex) {
            recordPaymentCallback(provider, request, "FAILED", ex.getMessage());
            throw ex;
        }
        OrderItem order = requiredOrder(payment.orderNo());
        if ("SUCCESS".equals(payment.status())) {
            recordPaymentCallback(provider, request, "IDEMPOTENT", "duplicate callback ignored");
            appendOperation("PAYMENT_CALLBACK_IDEMPOTENT", "PAYMENT", payment.paymentNo(), provider + " duplicate callback ignored");
            return order;
        }
        if (!"SUCCESS".equalsIgnoreCase(defaultText(request.status(), ""))) {
            PaymentItem failed = new PaymentItem(
                payment.paymentNo(),
                payment.orderNo(),
                payment.userId(),
                payment.method(),
                payment.amount(),
                "FAILED",
                defaultText(request.channelTradeNo(), payment.channelTradeNo()),
                payment.createdAt(),
                null
            );
            payments.put(failed.paymentNo(), failed);
            persistPaymentSnapshot(failed);
            recordPaymentCallback(provider, request, "FAILED", "callback marked failed");
            appendOperation("PAYMENT_CALLBACK_FAILED", "PAYMENT", failed.paymentNo(), provider + " callback marked failed");
            return order;
        }
        try {
            OrderStateMachine.assertCanAcceptPaymentCallback(order);
        } catch (IllegalStateException ex) {
            recordPaymentCallback(provider, request, "FAILED", ex.getMessage());
            throw ex;
        }
        OffsetDateTime paidAt = OffsetDateTime.now();
        PaymentItem paid = new PaymentItem(
            payment.paymentNo(),
            payment.orderNo(),
            payment.userId(),
            payment.method(),
            payment.amount(),
            "SUCCESS",
            defaultText(request.channelTradeNo(), payment.channelTradeNo()),
            payment.createdAt(),
            paidAt
        );
        payments.put(paid.paymentNo(), paid);
        persistPaymentSnapshot(paid);
        recordPaymentCallback(provider, request, "SUCCESS", "callback accepted");
        appendOperation("PAYMENT_CALLBACK_SUCCESS", "PAYMENT", paid.paymentNo(), provider + " callback accepted");
        return dispatchPaidOrder(order.withPayment(paid.paymentNo(), paid.method()), paidAt);
    }

    private void ensurePaymentCallbackMatches(PaymentItem payment, PaymentCallbackRequest request) {
        String callbackOrderNo = request == null ? "" : defaultText(request.orderNo(), "");
        if (StringUtils.hasText(callbackOrderNo) && !Objects.equals(callbackOrderNo, payment.orderNo())) {
            throw new IllegalArgumentException("payment callback order mismatch");
        }
    }

    public synchronized OrderItem completeManualOrder(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        OrderStateMachine.assertCanCompleteManual(order);
        OrderItem next = order.withStatus(
            OrderStatus.DELIVERED,
            "管理员已确认人工充值完成",
            OffsetDateTime.now()
        );
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        appendOperation("ORDER_COMPLETE_MANUAL", "ORDER", orderNo, "manual order completed");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem retryProcurement(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        OrderStateMachine.assertCanRetryProcurement(order);
        OrderItem next = procureWithFallback(order, "管理员手动重试");
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        appendOperation("ORDER_RETRY", "ORDER", orderNo, "manual retry");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem retryProcurementWithChannel(String orderNo, Long channelId) {
        OrderItem order = requiredOrder(orderNo);
        OrderStateMachine.assertCanRetryProcurement(order);
        GoodsChannelItem channel = goodsChannels.get(channelId);
        if (channel == null || !Objects.equals(channel.goodsId(), order.goodsId())) {
            throw new IllegalArgumentException("channel not found");
        }

        List<ChannelAttemptItem> attempts = new ArrayList<>(order.channelAttempts());
        SupplierItem supplier = suppliers.get(channel.supplierId());
        if (supplier == null) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商不存在"));
        } else if (!"ENABLED".equals(channel.status())) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：渠道已停用"));
        } else if (!"ENABLED".equals(supplier.status())) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商已停用"));
        } else if (supplier.balance().compareTo(order.payAmount()) < 0) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商余额不足"));
        } else {
            attempts.add(attempt(channel, "SUCCESS", "指定渠道采购成功，模拟充值已完成"));
            OrderItem delivered = order.withProcurementResult(
                OrderStatus.DELIVERED,
                List.of("直充成功：" + channel.supplierName() + " / " + channel.supplierGoodsId()),
                List.copyOf(attempts),
                "指定渠道重试成功：已通过 " + channel.supplierName() + " 完成充值",
                order.paidAt() == null ? OffsetDateTime.now() : order.paidAt(),
                OffsetDateTime.now()
            );
            orders.put(orderNo, delivered);
            persistOrderSnapshot(delivered);
            appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel retry succeeded");
            publishOrder(delivered);
            return delivered;
        }

        OrderItem failed = order.withProcurementResult(
            OrderStatus.FAILED,
            List.of(),
            List.copyOf(attempts),
            "指定渠道重试失败：" + channel.supplierName() + " / " + channel.supplierGoodsId(),
            order.paidAt(),
            null
        );
        orders.put(orderNo, failed);
        persistOrderSnapshot(failed);
        appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel retry failed");
        publishOrder(failed);
        return failed;
    }

    public synchronized OrderItem refundOrder(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        if (order.status() == OrderStatus.REFUNDED) {
            return order;
        }
        OrderStateMachine.assertCanRefund(order);
        createRefund(order, "管理员手动退款");
        OrderItem next = order.withStatus(
            OrderStatus.REFUNDED,
            "管理员已执行模拟退款",
            order.deliveredAt()
        );
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        appendOperation("ORDER_REFUND", "ORDER", orderNo, "manual refund");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem markOrderSuccess(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        OrderStateMachine.assertCanManualMarkSuccess(order);
        OffsetDateTime now = OffsetDateTime.now();
        OrderItem next = order.withProcurementResult(
            OrderStatus.DELIVERED,
            order.deliveryItems().isEmpty() ? List.of("管理员手动处理：订单已标记成功") : order.deliveryItems(),
            order.channelAttempts(),
            "管理员手动处理：订单已标记成功",
            order.paidAt() == null ? now : order.paidAt(),
            now
        );
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        appendOperation("ORDER_MANUAL_SUCCESS", "ORDER", orderNo, "manual mark success");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem markOrderFailed(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        OrderStateMachine.assertCanManualMarkFailed(order);
        OrderItem next = order.withStatus(OrderStatus.FAILED, "管理员手动处理：订单已标记失败", order.deliveredAt());
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        appendOperation("ORDER_MANUAL_FAILED", "ORDER", orderNo, "manual mark failed");
        publishOrder(next);
        return next;
    }

    public synchronized void deleteOrder(String orderNo) {
        OrderItem removed = orders.remove(orderNo);
        if (removed == null) {
            throw new IllegalArgumentException("order not found");
        }
        appendOperation("ORDER_DELETE", "ORDER", orderNo, "manual delete order");
    }

    public synchronized GoodsItem createGoods(CreateGoodsRequest request) {
        Long id = allocateIncrementingId(goodsId, maxGoodsId());
        Long categoryId = request.categoryId() == null ? 1L : request.categoryId();
        CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        GoodsType type = request.type() == null ? GoodsType.CARD : request.type();
        Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), null);
        GoodsItem item = new GoodsItem(
            id,
            categoryId,
            category == null ? "未分类" : category.name(),
            firstText(request.goodsName(), request.name(), "新商品 " + id),
            firstText(request.name(), request.goodsName(), "新商品 " + id),
            defaultText(request.subTitle(), "MVP 内存商品"),
            defaultText(request.description(), "这是一个用于前端联调的内存商品。"),
            normalizeTextList(request.benefitDurations()),
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
            normalizeTextList(request.accountTypes()),
            defaultText(request.priceTemplateId(), "retail-default"),
            defaultText(request.priceMode(), "FIXED"),
            request.priceCoefficient() == null ? BigDecimal.ONE : request.priceCoefficient(),
            request.priceFixedAdd() == null ? BigDecimal.ZERO : request.priceFixedAdd(),
            request.stock() == null ? 5000 : request.stock(),
            0,
            defaultText(request.status(), "ON_SALE"),
            request.tags() == null ? List.of("new") : List.copyOf(request.tags()),
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

    public synchronized GoodsItem updateGoods(Long id, CreateGoodsRequest request) {
        GoodsItem current = findGoodsSnapshot(id).orElse(null);
        if (current == null) {
            throw new IllegalArgumentException("goods not found");
        }
        Long categoryId = request.categoryId() == null ? current.categoryId() : request.categoryId();
        CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
        GoodsType type = request.type() == null ? current.type() : request.type();
        Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), current.cardKindId());
        GoodsItem next = new GoodsItem(
            current.id(),
            categoryId,
            category == null ? current.categoryName() : category.name(),
            firstText(request.goodsName(), request.name(), current.goodsName()),
            firstText(request.name(), request.goodsName(), current.name()),
            defaultText(request.subTitle(), current.subTitle()),
            defaultText(request.description(), current.description()),
            request.benefitDurations() == null ? current.benefitDurations() : normalizeTextList(request.benefitDurations()),
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
            request.accountTypes() == null ? current.accountTypes() : normalizeTextList(request.accountTypes()),
            defaultText(request.priceTemplateId(), current.priceTemplateId()),
            defaultText(request.priceMode(), current.priceMode()),
            request.priceCoefficient() == null ? current.priceCoefficient() : request.priceCoefficient(),
            request.priceFixedAdd() == null ? current.priceFixedAdd() : request.priceFixedAdd(),
            request.stock() == null ? current.stock() : request.stock(),
            current.sales(),
            defaultText(request.status(), current.status()),
            request.tags() == null ? current.tags() : List.copyOf(request.tags()),
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

    public synchronized OrderItem createOrder(CreateOrderRequest request) {
        return createOrder(request, 90001L);
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request, Long userId) {
        UserItem user = findUserSnapshot(userId).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        if (request.goodsId() == null) {
            throw new IllegalArgumentException("goodsId is required");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        GoodsItem item = findGoodsSnapshot(request.goodsId()).orElse(null);
        if (item == null || !"ON_SALE".equals(item.status())) {
            throw new IllegalArgumentException("goods not found");
        }
        validateOrderPermission(user);
        validateRechargeAccount(item, request.rechargeAccount());

        OffsetDateTime now = OffsetDateTime.now();
        String orderNo = nextOrderNo(user.id());
        OrderItem order = buildOrder(
            orderNo,
            user,
            item,
            quantity,
            request,
            List.of(),
            OrderStatus.UNPAID,
            "订单已创建，等待支付",
            now,
            null,
            null
        );
        orders.put(orderNo, order);
        persistOrderSnapshot(order);
        publishOrder(order);
        return order;
    }

    public synchronized OrderItem payOrder(String orderNo) {
        return payOrder(orderNo, null, null);
    }

    public synchronized OrderItem payOrder(String orderNo, Long userId, PayOrderRequest request) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        if (!OrderStateMachine.canStartMockPayment(order.status())) {
            return order;
        }

        OffsetDateTime paidAt = OffsetDateTime.now();
        String method = normalizePayMethod(request == null ? "" : request.payMethod());
        PaymentItem payment = createSuccessfulPayment(order, method, paidAt);
        appendOperation("PAYMENT_CREATE", "PAYMENT", payment.paymentNo(), "mock payment succeeded");
        return dispatchPaidOrder(order.withPayment(payment.paymentNo(), payment.method()), paidAt);
    }

    private OrderItem dispatchPaidOrder(OrderItem order, OffsetDateTime paidAt) {
        if (order.goodsType() == GoodsType.CARD) {
            return deliverCardsAfterPayment(order, paidAt);
        }
        if (order.goodsType() == GoodsType.DIRECT) {
            OrderItem paidOrder = order.withProcurementResult(
                OrderStatus.PROCURING,
                List.of(),
                List.of(),
                "支付成功，直充订单进入采购流程",
                paidAt,
                null
            );
            OrderItem next = procureWithFallback(paidOrder, "系统自动采购");
            orders.put(order.orderNo(), next);
            persistOrderSnapshot(next);
            publishOrder(next);
            return next;
        }

        OrderItem next = order.withProcurementResult(
            OrderStatus.WAITING_MANUAL,
            List.of(),
            List.of(),
            "支付成功，订单等待人工处理",
            paidAt,
            null
        );
        orders.put(order.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem cancelOrder(String orderNo) {
        return cancelOrder(orderNo, null);
    }

    public synchronized OrderItem cancelOrder(String orderNo, Long userId) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        OrderStateMachine.assertCanCancel(order);
        OrderItem next = order.withStatus(
            OrderStatus.CANCELLED,
            "订单已取消",
            order.deliveredAt()
        );
        orders.put(orderNo, next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return next;
    }

    private OrderItem deliverCardsAfterPayment(OrderItem order, OffsetDateTime paidAt) {
        GoodsItem item = findGoodsSnapshot(order.goodsId()).orElse(null);
        Long boundCardKindId = item == null ? null : item.cardKindId();
        Optional<List<String>> persistentDelivery = tryDeliverPersistentCards(order, boundCardKindId);
        if (persistentDelivery.isPresent()) {
            return completeCardDelivery(order, paidAt, persistentDelivery.get());
        }
        List<CardSecret> available = cards.values().stream()
            .filter(card -> boundCardKindId == null
                ? Objects.equals(card.goodsId(), order.goodsId())
                : Objects.equals(card.cardKindId(), boundCardKindId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .sorted(Comparator.comparing(CardSecret::id))
            .limit(order.quantity())
            .toList();
        if (available.size() < order.quantity()) {
            OrderItem failed = order.withProcurementResult(
                OrderStatus.FAILED,
                List.of(),
                List.of(),
                "支付成功，但卡密库存不足，等待退款或补货处理",
                paidAt,
                null
            );
            orders.put(order.orderNo(), failed);
            persistOrderSnapshot(failed);
            if (systemSetting.autoRefundEnabled() && order.paymentNo() != null) {
                createRefund(order, "卡密库存不足自动退款");
                failed = failed.withStatus(OrderStatus.REFUNDED, "卡密库存不足，系统已自动退款", null);
                orders.put(order.orderNo(), failed);
                persistOrderSnapshot(failed);
            }
            publishOrder(failed);
            return failed;
        }

        List<String> deliveryItems = new ArrayList<>();
        for (CardSecret card : available) {
            CardSecret used = card.delivered(order.orderNo());
            cards.put(used.id(), used);
            deliveryItems.add(used.content());
        }
        if (boundCardKindId == null) {
            refreshGoodsStock(order.goodsId());
        } else {
            refreshGoodsStockForCardKind(boundCardKindId);
        }

        return completeCardDelivery(order, paidAt, deliveryItems);
    }

    private OrderItem completeCardDelivery(OrderItem order, OffsetDateTime paidAt, List<String> deliveryItems) {
        OrderItem next = order.withProcurementResult(
            OrderStatus.DELIVERED,
            List.copyOf(deliveryItems),
            List.of(),
            "支付成功，卡密已自动发货",
            paidAt,
            OffsetDateTime.now()
        );
        orders.put(order.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return next;
    }

    private Optional<List<String>> tryDeliverPersistentCards(OrderItem order, Long cardKindId) {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<String> deliveryItems = persistentOrderStore.deliverCardsForOrder(order, cardKindId);
            if (deliveryItems.size() == order.quantity()) {
                return Optional.of(deliveryItems);
            }
            appendOperation("PERSISTENCE_CARD_DELIVERY_FALLBACK", "ORDER", order.orderNo(), "persistent card delivery returned incomplete result");
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_CARD_DELIVERY_FALLBACK", "ORDER", order.orderNo(), persistenceErrorMessage(ex));
        }
        return Optional.empty();
    }

    private OrderItem procureWithFallback(OrderItem order, String trigger) {
        List<GoodsChannelItem> channels = listGoodsChannels(order.goodsId()).stream()
            .filter(channel -> "ENABLED".equals(channel.status()))
            .toList();
        List<ChannelAttemptItem> attempts = new ArrayList<>();

        if (channels.isEmpty()) {
            ChannelAttemptItem attempt = new ChannelAttemptItem(
                null,
                null,
                "-",
                "-",
                0,
                "FAILED",
                "没有可用上游渠道",
                OffsetDateTime.now()
            );
            return order.withProcurementResult(
                OrderStatus.FAILED,
                List.of(),
                List.of(attempt),
                trigger + "失败：没有可用上游渠道",
                order.paidAt(),
                null
            );
        }

        for (GoodsChannelItem channel : channels) {
            SupplierItem supplier = suppliers.get(channel.supplierId());
            if (supplier == null) {
                attempts.add(attempt(channel, "FAILED", "供应商不存在"));
                continue;
            }
            if (!"ENABLED".equals(supplier.status())) {
                attempts.add(attempt(channel, "FAILED", "供应商已停用"));
                continue;
            }
            if (supplier.balance().compareTo(order.payAmount()) < 0) {
                attempts.add(attempt(channel, "FAILED", "供应商余额不足"));
                continue;
            }

            attempts.add(attempt(channel, "SUCCESS", "采购成功，模拟充值已完成"));
            return order.withProcurementResult(
                OrderStatus.DELIVERED,
                List.of("直充成功：" + channel.supplierName() + " / " + channel.supplierGoodsId()),
                List.copyOf(attempts),
                trigger + "成功：已通过 " + channel.supplierName() + " 完成充值",
                order.paidAt(),
                OffsetDateTime.now()
            );
        }

        return order.withProcurementResult(
            OrderStatus.FAILED,
            List.of(),
            List.copyOf(attempts),
            trigger + "失败：所有渠道均不可用",
            order.paidAt(),
            null
        );
    }

    private ChannelAttemptItem attempt(GoodsChannelItem channel, String status, String message) {
        return new ChannelAttemptItem(
            channel.id(),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            channel.priority(),
            status,
            message,
            OffsetDateTime.now()
        );
    }

    public DeliveryResult deliveryResult(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        return deliveryResult(orderNo, order.userId());
    }

    public DeliveryResult deliveryResult(String orderNo, Long userId) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        List<DeliveryCardItem> deliveredCards = cards.values().stream()
            .filter(card -> order.orderNo().equals(card.orderNo()))
            .sorted(Comparator.comparing(CardSecret::id))
            .map(card -> new DeliveryCardItem(
                card.cardNo(),
                card.secret(),
                "卡密已显示，请尽快使用。系统不会在日志中记录明文。"
            ))
            .toList();
        boolean viewedBefore = !deliveredCards.isEmpty() && !viewedDeliveryOrders.add(order.orderNo());
        return new DeliveryResult(
            order.orderNo(),
            order.status(),
            order.goodsType(),
            order.rechargeAccount(),
            order.deliveryItems(),
            order.deliveryMessage(),
            deliveredCards,
            viewedBefore
        );
    }

    private OrderItem requiredOrder(String orderNo) {
        OrderItem order = orders.get(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        return expireOrderIfNeeded(order, OffsetDateTime.now());
    }

    private void expireStaleUnpaidOrders() {
        OffsetDateTime now = OffsetDateTime.now();
        orders.values().forEach(order -> expireOrderIfNeeded(order, now));
    }

    private OrderItem expireOrderIfNeeded(OrderItem order, OffsetDateTime now) {
        if (!OrderStateMachine.canExpirePayment(order.status())) {
            return order;
        }
        if (order.createdAt() == null || order.createdAt().plus(PAYMENT_TIMEOUT).isAfter(now)) {
            return order;
        }
        OrderItem expired = order.withStatus(
            OrderStatus.CANCELLED,
            "订单支付超时，已自动取消",
            order.deliveredAt()
        );
        orders.put(order.orderNo(), expired);
        persistOrderSnapshot(expired);
        publishOrder(expired);
        return expired;
    }

    private void publishOrder(OrderItem order) {
        appendSmsLogIfNeeded(order);
        realtimeBroadcaster.publish(order);
    }

    private void appendSmsLogIfNeeded(OrderItem order) {
        if (!List.of(OrderStatus.DELIVERED, OrderStatus.FAILED, OrderStatus.REFUNDED).contains(order.status())) {
            return;
        }
        boolean exists = smsLogs.values().stream()
            .anyMatch(log -> Objects.equals(log.orderNo(), order.orderNo()) && Objects.equals(log.templateType(), String.valueOf(order.status())));
        if (exists) {
            return;
        }
        String mobile = order.rechargeAccount();
        if (!StringUtils.hasText(mobile) || !mobile.matches("1\\d{10}")) {
            mobile = order.buyerAccount();
        }
        boolean validMobile = StringUtils.hasText(mobile) && mobile.matches("1\\d{10}");
        String content = "订单" + order.orderNo() + "状态：" + order.status() + "，商品：" + order.goodsName();
        String status = systemSetting.smsEnabled() && validMobile ? "SENT" : "SKIPPED";
        String error = systemSetting.smsEnabled() ? (validMobile ? "" : "手机号不可用") : "短信未启用";
        Long id = smsLogId.getAndIncrement();
        SmsLogItem log = new SmsLogItem(id, order.orderNo(), validMobile ? mobile : "", String.valueOf(order.status()), content, status, error, OffsetDateTime.now());
        smsLogs.put(id, log);
        persistSmsLog(log);
    }

    private SupplierItem requiredSupplier(Long id) {
        SupplierItem item = suppliers.get(id);
        if (item == null) {
            item = persistentSuppliers().stream()
                .flatMap(List::stream)
                .filter(candidate -> Objects.equals(candidate.id(), id))
                .findFirst()
                .orElse(null);
            if (item != null) {
                suppliers.put(id, item);
            }
        }
        if (item == null) {
            throw new IllegalArgumentException("supplier not found");
        }
        return item;
    }

    private SupplierItem testKasushouConnection(SupplierItem item) {
        validateKasushouCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            return item.withLastSyncAt(OffsetDateTime.now());
        }

        JsonNode root = kasushouPostJson(item, "/api/v1/user/info", Map.of(), "test connection");
        ensureKasushouOk(root, "test connection");
        return item.withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshKasushouBalance(SupplierItem item) {
        validateKasushouCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            return item.withBalance(item.balance() == null ? BigDecimal.ZERO : item.balance());
        }
        JsonNode root = kasushouPostJson(item, "/api/v1/user/info", Map.of(), "balance refresh");
        ensureKasushouOk(root, "balance refresh");
        return item.withBalance(kasushouBalance(root));
    }

    private RemoteGoodsSyncResult fetchKasushouGoods(SupplierItem item, Long cateId, String keyword, int page, int limit) {
        validateKasushouCredentials(item);

        JsonNode cateRoot = kasushouPostJson(item, "/api/v1/goods/cate", Map.of(), "category sync");
        ensureKasushouOk(cateRoot, "category sync");
        List<Map<String, Object>> categories = kasushouCategories(cateRoot.path("data"));

        Map<String, Object> listBody = new java.util.LinkedHashMap<>();
        listBody.put("cate_id", cateId == null ? "" : cateId);
        listBody.put("keyword", defaultText(keyword, ""));
        listBody.put("limit", limit);
        listBody.put("page", page);

        JsonNode listRoot = kasushouPostJson(item, "/api/v1/goods/list", listBody, "goods list sync");
        ensureKasushouOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.path("list");
        if (!listNode.isArray()) {
            throw new IllegalStateException("kasushou goods list sync failed: data.list is missing");
        }
        int total = intValue(data.path("total"), listNode.size());
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            items.add(remoteGoodsItem(node));
        }
        OffsetDateTime syncedAt = OffsetDateTime.now();
        return new RemoteGoodsSyncResult(
            item.id(),
            syncedAt,
            total,
            items,
            categories,
            page,
            limit,
            "synced " + items.size() + " kasushou goods from remote total " + total
        );
    }

    private void validateKasushouCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("kasushou baseUrl is required");
        }
        if (!StringUtils.hasText(kasushouIdentity(item))) {
            throw new IllegalArgumentException("kasushou appId is required");
        }
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("kasushou apiKey is required");
        }
    }

    private JsonNode kasushouPostJson(SupplierItem item, String path, Object bodyObject, String action) {
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        String apiKey = supplierApiKeys.get(item.id());
        String body = KasushouSignatureUtil.sortedJsonBody(bodyObject);
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, String> headers = KasushouSignatureUtil.headers(timestamp, kasushouIdentity(item), bodyObject, apiKey);
        HttpRequest request = HttpRequest.newBuilder(kasushouUri(item.baseUrl(), path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Sign", headers.get("Sign"))
            .header("Timestamp", headers.get("Timestamp"))
            .header("UserId", headers.get("UserId"))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("kasushou " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kasushou " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("kasushou " + action + " failed: invalid JSON response");
        } catch (Exception ex) {
            throw new IllegalStateException("kasushou " + action + " failed: " + ex.getMessage());
        }
    }

    private void ensureKasushouOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 200) {
            String message = textValue(root, "msg", "message", "error");
            throw new IllegalStateException("kasushou " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private BigDecimal kasushouBalance(JsonNode root) {
        BigDecimal balance = optionalDecimalValue(root,
            "balance", "money", "user_money", "userMoney", "amount", "account_balance", "accountBalance");
        if (balance != null) {
            return balance;
        }
        JsonNode data = root.path("data");
        balance = optionalDecimalValue(data,
            "balance", "money", "user_money", "userMoney", "amount", "account_balance", "accountBalance");
        if (balance != null) {
            return balance;
        }
        throw new IllegalStateException("kasushou balance refresh failed: balance field is missing");
    }

    private List<Map<String, Object>> kasushouCategories(JsonNode data) {
        JsonNode categoryNode = data.isArray() ? data : firstExisting(data, "list", "cate", "cates", "category", "categories");
        if (categoryNode == null || !categoryNode.isArray()) {
            return List.of();
        }
        return OBJECT_MAPPER.convertValue(categoryNode, LIST_MAP_TYPE);
    }

    private RemoteGoodsItem remoteGoodsItem(JsonNode node) {
        return new RemoteGoodsItem(
            textValue(node, "id", "goods_id", "goodsId"),
            textValue(node, "goods_name", "goodsName", "name", "title"),
            textValue(node, "goods_type", "goodsType", "type"),
            decimalValue(node, "goods_price", "goodsPrice", "price"),
            decimalValue(node, "face_value", "faceValue", "face"),
            intValue(firstExisting(node, "stock_num", "stockNum", "stock", "num"), 0),
            textValue(node, "status", "state"),
            booleanValue(node, "can_buy", "canBuy"),
            booleanValue(node, "can_no_buy", "canNoBuy", "can_not_buy"),
            OBJECT_MAPPER.convertValue(node, MAP_TYPE)
        );
    }

    private RemoteGoodsSyncResult mockKasushouGoodsSyncResult(Long supplierId, Long cateId, String keyword, int page, int limit) {
        OffsetDateTime syncedAt = OffsetDateTime.now();
        List<Map<String, Object>> categories = List.of(
            Map.of("id", 101, "name", "会员权益"),
            Map.of("id", 102, "name", "影音娱乐")
        );
        List<RemoteGoodsItem> items = List.of(
            new RemoteGoodsItem(
                "KSS-MOCK-1001",
                "卡速售模拟商品 - 视频会员月卡",
                "CARD",
                BigDecimal.valueOf(18.80),
                BigDecimal.valueOf(30),
                128,
                "ON_SALE",
                true,
                false,
                Map.of("id", "KSS-MOCK-1001", "cate_id", cateId == null ? 101 : cateId, "mock", true)
            ),
            new RemoteGoodsItem(
                "KSS-MOCK-1002",
                "卡速售模拟商品 - 游戏点券直充",
                "RECHARGE",
                BigDecimal.valueOf(96.50),
                BigDecimal.valueOf(100),
                64,
                "ON_SALE",
                true,
                false,
                Map.of("id", "KSS-MOCK-1002", "keyword", defaultText(keyword, ""), "mock", true)
            ),
            new RemoteGoodsItem(
                "KSS-MOCK-1003",
                "卡速售模拟商品 - 库存不足样例",
                "CARD",
                BigDecimal.valueOf(9.90),
                BigDecimal.valueOf(10),
                0,
                "SOLD_OUT",
                false,
                true,
                Map.of("id", "KSS-MOCK-1003", "stock_num", 0, "mock", true)
            )
        );
        int safeLimit = Math.max(1, limit);
        int fromIndex = Math.min(items.size(), (page - 1) * safeLimit);
        int toIndex = Math.min(items.size(), fromIndex + safeLimit);
        List<RemoteGoodsItem> pageItems = items.subList(fromIndex, toIndex);
        return new RemoteGoodsSyncResult(
            supplierId,
            syncedAt,
            items.size(),
            pageItems,
            categories,
            page,
            limit,
            "mocked kasushou goods sync for placeholder baseUrl"
        );
    }

    private URI kasushouUserInfoUri(String baseUrl) {
        return kasushouUri(baseUrl, "/api/v1/user/info");
    }

    private URI kasushouUri(String baseUrl, String path) {
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        return URI.create(normalizedBaseUrl + path);
    }

    private JsonNode firstExisting(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String textValue(JsonNode node, String... fieldNames) {
        JsonNode value = firstExisting(node, fieldNames);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private BigDecimal decimalValue(JsonNode node, String... fieldNames) {
        BigDecimal value = optionalDecimalValue(node, fieldNames);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal optionalDecimalValue(JsonNode node, String... fieldNames) {
        JsonNode value = firstExisting(node, fieldNames);
        if (value == null || value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int intValue(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(node.asText())) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    private Boolean booleanValue(JsonNode node, String... fieldNames) {
        JsonNode value = firstExisting(node, fieldNames);
        if (value == null || value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String normalized = normalize(value.asText());
        return "1".equals(normalized)
            || "true".equals(normalized)
            || "yes".equals(normalized)
            || "y".equals(normalized)
            || "on".equals(normalized);
    }

    private boolean isKasushouSupplier(SupplierItem item) {
        return isKasushouPlatform(item.platformType());
    }

    private boolean isKasushouPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        return "kasushou_2".equals(normalizedPlatformType)
            || "kasushou".equals(normalizedPlatformType)
            || "kasu".equals(normalizedPlatformType);
    }

    private String kasushouIdentity(SupplierItem item) {
        return firstText(item.userId(), item.appId(), item.appKey());
    }

    private boolean isPlaceholderBaseUrl(String baseUrl) {
        String trimmed = defaultText(baseUrl, "").trim();
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.contains("example") || normalized.contains("你的") || normalized.contains("占位")) {
            return true;
        }
        try {
            String host = URI.create(trimmed).getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return "example.com".equals(normalizedHost)
                || normalizedHost.endsWith(".example.com")
                || normalizedHost.endsWith(".example")
                || normalizedHost.contains(".example.");
        } catch (Exception ex) {
            return false;
        }
    }

    private int normalizedTimeoutSeconds(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return 10;
        }
        return Math.max(1, Math.min(timeoutSeconds, 60));
    }

    private String normalizedCallbackUrl(String callbackUrl) {
        return defaultText(callbackUrl, "");
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return defaultText(value, "");
        }
        return value.substring(0, maxLength) + "...";
    }

    private UserItem requiredUser(Long id) {
        UserItem item = findUserSnapshot(id).orElse(null);
        if (item == null) {
            throw new IllegalArgumentException("user not found");
        }
        return item;
    }

    public synchronized CardImportResult importCards(Long targetGoodsId, CardImportRequest request) {
        if (findGoodsSnapshot(targetGoodsId).isEmpty()) {
            throw new IllegalArgumentException("goods not found");
        }
        List<String> lines = cardLines(request);
        int duplicateCount = 0;
        List<Integer> failedLines = new ArrayList<>();
        Set<String> seenInRequest = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>(cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), targetGoodsId))
            .map(CardSecret::content)
            .toList());

        int lineNo = 0;
        int successCount = 0;
        for (String raw : lines) {
            lineNo++;
            String content = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(content)) {
                failedLines.add(lineNo);
                continue;
            }
            if (existing.contains(content) || !seenInRequest.add(content)) {
                duplicateCount++;
                continue;
            }
            Long id = cardId.getAndIncrement();
            CardSecret card = new CardSecret(
                id,
                targetGoodsId,
                "CARD-" + id,
                mask(content),
                content,
                mask(content),
                "AVAILABLE",
                null,
                OffsetDateTime.now(),
                null
            );
            cards.put(id, card);
            persistImportedCard(card);
            successCount++;
        }
        refreshGoodsStock(targetGoodsId);
        return new CardImportResult(targetGoodsId, lines.size(), successCount, duplicateCount, List.copyOf(failedLines));
    }

    public synchronized CardImportResult importCardKindCards(Long targetCardKindId, CardImportRequest request) {
        if (!cardKinds.containsKey(targetCardKindId)) {
            throw new IllegalArgumentException("card kind not found");
        }
        List<String> lines = cardLines(request);
        int duplicateCount = 0;
        List<Integer> failedLines = new ArrayList<>();
        Set<String> seenInRequest = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>(cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), targetCardKindId))
            .map(CardSecret::content)
            .toList());

        int lineNo = 0;
        int successCount = 0;
        OffsetDateTime importedAt = OffsetDateTime.now();
        for (String raw : lines) {
            lineNo++;
            String content = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(content)) {
                failedLines.add(lineNo);
                continue;
            }
            if (existing.contains(content) || !seenInRequest.add(content)) {
                duplicateCount++;
                continue;
            }
            Long id = cardId.getAndIncrement();
            CardSecret card = new CardSecret(
                id,
                null,
                "CARD-" + id,
                mask(content),
                content,
                mask(content),
                "AVAILABLE",
                null,
                importedAt,
                null,
                targetCardKindId
            );
            cards.put(id, card);
            persistImportedCard(card);
            successCount++;
        }
        refreshGoodsStockForCardKind(targetCardKindId);
        return new CardImportResult(null, lines.size(), successCount, duplicateCount, List.copyOf(failedLines), targetCardKindId);
    }

    public List<CardSecret> listCards(Long goodsId) {
        return cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), goodsId))
            .map(card -> new CardSecret(
                card.id(),
                card.goodsId(),
                card.cardNo(),
                card.preview(),
                card.preview(),
                card.preview(),
                card.status(),
                card.orderNo(),
                card.importedAt(),
                card.deliveredAt(),
                card.cardKindId()
            ))
            .sorted(Comparator.comparing(CardSecret::id))
            .toList();
    }

    public List<CardSecret> listCardKindCards(Long cardKindId) {
        if (!cardKinds.containsKey(cardKindId)) {
            throw new IllegalArgumentException("card kind not found");
        }
        return cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), cardKindId))
            .map(card -> new CardSecret(
                card.id(),
                card.goodsId(),
                card.cardNo(),
                card.preview(),
                card.preview(),
                card.preview(),
                card.status(),
                card.orderNo(),
                card.importedAt(),
                card.deliveredAt(),
                card.cardKindId()
            ))
            .sorted(Comparator.comparing(CardSecret::id))
            .toList();
    }

    private OrderItem buildOrder(
        String orderNo,
        UserItem user,
        GoodsItem item,
        int quantity,
        CreateOrderRequest request,
        List<String> deliveryItems,
        OrderStatus status,
        String deliveryMessage,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime deliveredAt
    ) {
        return new OrderItem(
            orderNo,
            user.id(),
            firstText(user.mobile(), user.email(), user.nickname()),
            item.id(),
            item.goodsName(),
            item.type(),
            item.platform(),
            quantity,
            item.price(),
            item.price().multiply(BigDecimal.valueOf(quantity)),
            status,
            request.rechargeAccount(),
            request.buyerRemark(),
            request.requestId(),
            null,
            null,
            List.copyOf(deliveryItems),
            List.of(),
            deliveryMessage,
            createdAt,
            paidAt,
            deliveredAt
        );
    }

    private String nextOrderNo(Long userId) {
        return "xiyi" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + userId + String.format("%08d", orderSeq.getAndIncrement());
    }

    private String nextPaymentNo() {
        return "PAY" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", paymentSeq.getAndIncrement());
    }

    private String nextRefundNo() {
        return "RF" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", refundSeq.getAndIncrement());
    }

    private PaymentItem createSuccessfulPayment(OrderItem order, String method, OffsetDateTime paidAt) {
        String paymentNo = nextPaymentNo();
        PaymentItem payment = new PaymentItem(
            paymentNo,
            order.orderNo(),
            order.userId(),
            method,
            order.payAmount(),
            "SUCCESS",
            "MOCK-" + paymentNo,
            paidAt,
            paidAt
        );
        payments.put(paymentNo, payment);
        persistPaymentSnapshot(payment);
        return payment;
    }

    private RefundItem createRefund(OrderItem order, String reason) {
        Optional<RefundItem> existing = refunds.values().stream()
            .filter(refund -> Objects.equals(refund.orderNo(), order.orderNo()))
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        OffsetDateTime now = OffsetDateTime.now();
        RefundItem refund = new RefundItem(
            nextRefundNo(),
            order.orderNo(),
            order.paymentNo(),
            order.userId(),
            order.payAmount(),
            "SUCCESS",
            reason,
            now,
            now
        );
        refunds.put(refund.refundNo(), refund);
        persistRefundSnapshot(refund);
        appendOperation("REFUND_CREATE", "REFUND", refund.refundNo(), reason);
        return refund;
    }

    public void recordPaymentCallback(String provider, PaymentCallbackRequest request, String result, String message) {
        Long id = paymentCallbackLogId.getAndIncrement();
        PaymentCallbackLogItem log = new PaymentCallbackLogItem(
            id,
            defaultText(provider, ""),
            request == null ? "" : defaultText(request.paymentNo(), ""),
            request == null ? "" : defaultText(request.orderNo(), ""),
            request == null ? "" : defaultText(request.status(), ""),
            request == null ? "" : defaultText(request.channelTradeNo(), ""),
            defaultText(result, ""),
            defaultText(message, ""),
            OffsetDateTime.now()
        );
        paymentCallbackLogs.put(id, log);
        persistPaymentCallbackLog(log);
    }

    private void persistOrderSnapshot(OrderItem order) {
        if (persistentOrderStore == null || order == null) {
            return;
        }
        try {
            persistentOrderStore.saveOrderSnapshot(order);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ORDER", order.orderNo(), persistenceErrorMessage(ex));
        }
    }

    private void persistPaymentSnapshot(PaymentItem payment) {
        if (persistentOrderStore == null || payment == null) {
            return;
        }
        try {
            persistentOrderStore.savePaymentSnapshot(payment, null);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PAYMENT", payment.paymentNo(), persistenceErrorMessage(ex));
        }
    }

    private void persistPaymentCallbackLog(PaymentCallbackLogItem log) {
        if (persistentOrderStore == null || log == null) {
            return;
        }
        try {
            persistentOrderStore.savePaymentCallbackLog(log);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PAYMENT_CALLBACK", String.valueOf(log.id()), persistenceErrorMessage(ex));
        }
    }

    private void persistRefundSnapshot(RefundItem refund) {
        if (persistentOrderStore == null || refund == null) {
            return;
        }
        try {
            persistentOrderStore.saveRefundSnapshot(refund);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "REFUND", refund.refundNo(), persistenceErrorMessage(ex));
        }
    }

    private void persistImportedCard(CardSecret card) {
        if (persistentOrderStore == null || card == null || (card.goodsId() == null && card.cardKindId() == null)) {
            return;
        }
        try {
            persistentOrderStore.saveImportedCard(card);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CARD", String.valueOf(card.id()), persistenceErrorMessage(ex));
        }
    }

    private void persistCategorySnapshot(CategoryItem category) {
        if (catalogPersistenceStore == null || category == null) {
            return;
        }
        try {
            catalogPersistenceStore.saveCategorySnapshot(category);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CATEGORY", String.valueOf(category.id()), persistenceErrorMessage(ex));
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
        }
    }

    private void persistUserSnapshot(UserItem user) {
        if (catalogPersistenceStore == null || user == null) {
            return;
        }
        try {
            catalogPersistenceStore.saveUserSnapshot(user);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "USER", String.valueOf(user.id()), persistenceErrorMessage(ex));
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
        }
    }

    private void persistSupplier(SupplierItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveSupplier(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SUPPLIER", String.valueOf(item.id()), persistenceErrorMessage(ex));
        }
    }

    private void deletePersistentSupplier(Long id) {
        if (configPersistenceStore == null || id == null) {
            return;
        }
        try {
            configPersistenceStore.deleteSupplier(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SUPPLIER", String.valueOf(id), persistenceErrorMessage(ex));
        }
    }

    private void persistGoodsChannel(GoodsChannelItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveGoodsChannel(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS_CHANNEL", String.valueOf(item.id()), persistenceErrorMessage(ex));
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
        }
    }

    private void persistUserGroup(UserGroupItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveUserGroup(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "USER_GROUP", String.valueOf(item.id()), persistenceErrorMessage(ex));
        }
    }

    private void persistGroupRules(Long groupId, String ruleType, List<GroupRuleItem> rules) {
        if (configPersistenceStore == null || groupId == null || !StringUtils.hasText(ruleType)) {
            return;
        }
        try {
            configPersistenceStore.replaceGroupRules(groupId, ruleType, rules == null ? List.of() : rules);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GROUP_RULE", groupId + ":" + ruleType, persistenceErrorMessage(ex));
        }
    }

    private void persistSystemSetting(SystemSettingItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveSystemSetting(item);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SYSTEM_SETTING", "GLOBAL", persistenceErrorMessage(ex));
        }
    }

    private void persistOperationLog(OperationLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveOperationLog(log);
        } catch (RuntimeException ignored) {
            // Avoid recursive operation-log writes when the audit mirror itself is unavailable.
        }
    }

    private void persistSmsLog(SmsLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveSmsLog(log);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SMS_LOG", String.valueOf(log.id()), persistenceErrorMessage(ex));
        }
    }

    private void persistOpenApiLog(OpenApiLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveOpenApiLog(log);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "OPEN_API_LOG", String.valueOf(log.id()), persistenceErrorMessage(ex));
        }
    }

    private Optional<List<CategoryItem>> persistentCategories() {
        if (catalogPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<CategoryItem> items = catalogPersistenceStore.listCategories();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
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
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "GOODS", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<UserItem>> persistentUsers() {
        if (catalogPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<UserItem> items = catalogPersistenceStore.listUsers();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "USER", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<CardKindItem>> persistentCardKinds() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<CardKindItem> items = configPersistenceStore.listCardKinds();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
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
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "RECHARGE_FIELD", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<SupplierItem>> persistentSuppliers() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<SupplierItem> items = configPersistenceStore.listSuppliers();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
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
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "GOODS_CHANNEL", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<UserGroupItem>> persistentUserGroups() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<UserGroupItem> items = configPersistenceStore.listUserGroups();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "USER_GROUP", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<GroupRuleItem>> persistentGroupRules() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<GroupRuleItem> items = configPersistenceStore.listGroupRules();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "GROUP_RULE", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private void loadPersistentSystemSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            Map<String, String> settings = configPersistenceStore.systemSettings();
            if (settings.isEmpty()) {
                return;
            }
            systemSetting = new SystemSettingItem(
                defaultText(settings.get("siteName"), systemSetting.siteName()),
                defaultText(settings.get("logoUrl"), systemSetting.logoUrl()),
                defaultText(settings.get("customerService"), systemSetting.customerService()),
                defaultText(settings.get("companyName"), systemSetting.companyName()),
                defaultText(settings.get("icpRecordNo"), systemSetting.icpRecordNo()),
                defaultText(settings.get("policeRecordNo"), systemSetting.policeRecordNo()),
                defaultText(settings.get("disclaimer"), systemSetting.disclaimer()),
                defaultText(settings.get("paymentMode"), systemSetting.paymentMode()),
                booleanSetting(settings, "autoRefundEnabled", systemSetting.autoRefundEnabled()),
                defaultText(settings.get("smsProvider"), systemSetting.smsProvider()),
                booleanSetting(settings, "smsEnabled", systemSetting.smsEnabled()),
                intSetting(settings, "upstreamSyncSeconds", systemSetting.upstreamSyncSeconds(), 5),
                booleanSetting(settings, "autoShelfEnabled", systemSetting.autoShelfEnabled()),
                booleanSetting(settings, "autoPriceEnabled", systemSetting.autoPriceEnabled()),
                booleanSetting(settings, "registrationEnabled", systemSetting.registrationEnabled()),
                normalizeRegistrationType(defaultText(settings.get("registrationType"), systemSetting.registrationType())),
                longSetting(settings, "defaultUserGroupId", systemSetting.defaultUserGroupId()),
                Map.of("ops", defaultText(settings.get("notification.ops"), systemSetting.notificationReceivers().getOrDefault("ops", "")))
            );
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SYSTEM_SETTING", "GLOBAL", persistenceErrorMessage(ex));
        }
    }

    private Optional<List<OrderItem>> persistentOrders() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<OrderItem> items = persistentOrderStore.listOrders();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "ORDER", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<OrderItem> persistentOrder(String orderNo) {
        if (persistentOrderStore == null || !StringUtils.hasText(orderNo)) {
            return Optional.empty();
        }
        try {
            return persistentOrderStore.findOrder(orderNo);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "ORDER", orderNo, persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<PaymentItem>> persistentPayments() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<PaymentItem> items = persistentOrderStore.listPayments();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "PAYMENT", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<PaymentCallbackLogItem>> persistentPaymentCallbackLogs() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<PaymentCallbackLogItem> items = persistentOrderStore.listPaymentCallbackLogs();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "PAYMENT_CALLBACK", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<RefundItem>> persistentRefunds() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<RefundItem> items = persistentOrderStore.listRefunds();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "REFUND", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<SmsLogItem>> persistentSmsLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<SmsLogItem> items = auditPersistenceStore.listSmsLogs();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SMS_LOG", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<List<OperationLogItem>> persistentOperationLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<OperationLogItem> items = auditPersistenceStore.listOperationLogs();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<List<OpenApiLogItem>> persistentOpenApiLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<OpenApiLogItem> items = auditPersistenceStore.listOpenApiLogs();
            return items.isEmpty() ? Optional.empty() : Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "OPEN_API_LOG", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
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

    private long maxUserGroupId() {
        long max = userGroups.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<UserGroupItem>> persistent = persistentUserGroups();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(UserGroupItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
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

    private long maxSupplierId() {
        long max = suppliers.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<SupplierItem>> persistent = persistentSuppliers();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(SupplierItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
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

    private long maxUserId() {
        long max = users.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<UserItem>> persistent = persistentUsers();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(UserItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
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

    private Optional<UserGroupItem> findUserGroupSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        UserGroupItem memory = userGroups.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<UserGroupItem> persistent = persistentUserGroups().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> userGroups.put(item.id(), item));
        return persistent;
    }

    private boolean userGroupNameExists(String name, Long excludeId) {
        String normalizedName = normalize(name);
        if (!StringUtils.hasText(normalizedName)) {
            return false;
        }
        boolean memoryMatch = userGroups.values().stream()
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(normalize(item.name()), normalizedName));
        if (memoryMatch) {
            return true;
        }
        return persistentUserGroups().stream()
            .flatMap(List::stream)
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(normalize(item.name()), normalizedName));
    }

    private List<UserItem> allUserSnapshots() {
        Map<Long, UserItem> snapshots = new java.util.LinkedHashMap<>();
        persistentUsers().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        users.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(UserItem::id))
            .toList();
    }

    private Optional<UserItem> findUserSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        UserItem memory = users.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<UserItem> persistent = persistentUsers().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> users.put(item.id(), item));
        return persistent;
    }

    private Optional<GoodsItem> findGoodsSnapshot(Long id) {
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

    private Optional<CategoryItem> findCategorySnapshot(Long id) {
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

    private List<GoodsItem> allGoodsSnapshots() {
        Map<Long, GoodsItem> snapshots = new java.util.LinkedHashMap<>();
        persistentGoods().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        goods.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    private Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long id) {
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

    private boolean supplierNameExists(String name, Long excludeId) {
        String normalizedName = normalize(name);
        if (!StringUtils.hasText(normalizedName)) {
            return false;
        }
        boolean memoryMatch = suppliers.values().stream()
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(normalize(item.name()), normalizedName));
        if (memoryMatch) {
            return true;
        }
        return persistentSuppliers().stream()
            .flatMap(List::stream)
            .anyMatch(item -> !Objects.equals(item.id(), excludeId) && Objects.equals(normalize(item.name()), normalizedName));
    }

    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        Long id = operationLogId.getAndIncrement();
        OperationLogItem log = new OperationLogItem(
            id,
            "system",
            action,
            resourceType,
            resourceId,
            remark,
            OffsetDateTime.now()
        );
        operationLogs.put(id, log);
        persistOperationLog(log);
    }

    private boolean isProductMonitorChannel(GoodsChannelItem channel) {
        GoodsItem item = findGoodsSnapshot(channel.goodsId()).orElse(null);
        return item != null
            && "ENABLED".equals(channel.status());
    }

    private ProductMonitorState ensureProductMonitorState(Long channelId, OffsetDateTime now) {
        return productMonitorStates.computeIfAbsent(channelId, id -> new ProductMonitorState(
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

    private ProductMonitorItem productMonitorItem(GoodsChannelItem channel, ProductMonitorState state) {
        GoodsItem item = findGoodsSnapshot(channel.goodsId()).orElse(null);
        return new ProductMonitorItem(
            channel.id(),
            channel.goodsId(),
            item == null ? "-" : item.goodsName(),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            isPrimaryProductMonitorChannel(channel),
            state.lastResult(),
            state.lastScanAt(),
            state.nextScanAt(),
            state.lastResult(),
            state.lastMessage(),
            state.scanCount(),
            state.changeCount()
        );
    }

    private boolean isPrimaryProductMonitorChannel(GoodsChannelItem channel) {
        return goodsChannels.values().stream()
            .filter(this::isProductMonitorChannel)
            .filter(item -> Objects.equals(item.goodsId(), channel.goodsId()))
            .min(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .map(item -> Objects.equals(item.id(), channel.id()))
            .orElse(false);
    }

    private MonitoredRemoteGoods monitoredRemoteGoods(
        GoodsItem current,
        GoodsChannelItem channel,
        SupplierItem supplier,
        int nextScanCount,
        boolean manual
    ) {
        Optional<RemoteGoodsItem> synced = Optional.ofNullable(remoteGoodsSyncResults.get(supplier.id()))
            .flatMap(result -> result.items().stream()
                .filter(item -> Objects.equals(item.supplierGoodsId(), channel.supplierGoodsId()))
                .findFirst());
        if (synced.isPresent()) {
            RemoteGoodsItem item = synced.get();
            return new MonitoredRemoteGoods(
                firstText(item.goodsName(), current.goodsName(), current.name()),
                item.goodsPrice() == null ? current.price() : item.goodsPrice(),
                item.stockNum() == null ? current.stock() : Math.max(0, item.stockNum()),
                item.canBuy() == Boolean.FALSE || item.canNoBuy() == Boolean.TRUE ? "OFF_SALE" : "ON_SALE"
            );
        }

        boolean shouldChange = manual || nextScanCount % 3 == 0;
        BigDecimal nextPrice = shouldChange ? current.price().add(BigDecimal.valueOf(0.10)) : current.price();
        Integer nextStock = shouldChange ? Math.max(0, current.stock() + 5) : current.stock();
        String baseTitle = current.goodsName().replaceAll("( 同步| 上游)+$", "");
        String nextTitle = shouldChange ? baseTitle + " 上游" : current.goodsName();
        return new MonitoredRemoteGoods(nextTitle, nextPrice, nextStock, current.status());
    }

    private GoodsItem applyMonitoredRemoteGoods(GoodsItem current, MonitoredRemoteGoods remote, List<String> changes) {
        String nextName = defaultText(remote.title(), current.goodsName());
        BigDecimal nextPrice = remote.price() == null ? current.price() : remote.price();
        Integer nextStock = remote.stock() == null ? current.stock() : remote.stock();
        String nextStatus = defaultText(remote.status(), current.status());

        if (!Objects.equals(current.goodsName(), nextName)) {
            changes.add("商品名称：" + current.goodsName() + " → " + nextName);
        }
        if (current.price().compareTo(nextPrice) != 0) {
            changes.add("价格：" + current.price().toPlainString() + " → " + nextPrice.toPlainString());
        }
        if (!Objects.equals(current.stock(), nextStock)) {
            changes.add("库存：" + current.stock() + " → " + nextStock);
        }
        if (!Objects.equals(current.status(), nextStatus)) {
            changes.add("状态：" + current.status() + " → " + nextStatus);
        }
        if (changes.isEmpty()) {
            return current;
        }

        OffsetDateTime now = OffsetDateTime.now();
        return new GoodsItem(
            current.id(),
            current.categoryId(),
            current.categoryName(),
            nextName,
            nextName,
            current.subTitle(),
            current.description(),
            current.benefitDurations(),
            current.coverUrl(),
            current.detailImages(),
            current.detailBlocks(),
            current.integrations(),
            current.pollingEnabled(),
            current.monitoringEnabled(),
            current.type(),
            current.platform(),
            nextPrice,
            current.originalPrice(),
            current.maxBuy(),
            current.requireRechargeAccount(),
            current.accountTypes(),
            current.priceTemplateId(),
            current.priceMode(),
            current.priceCoefficient(),
            current.priceFixedAdd(),
            nextStock,
            current.sales(),
            nextStatus,
            current.tags(),
            current.createdAt(),
            now,
            current.availablePlatforms(),
            current.forbiddenPlatforms(),
            current.cardKindId()
        );
    }

    private void trimProductMonitorLogs() {
        int overflow = productMonitorLogs.size() - 300;
        if (overflow <= 0) {
            return;
        }
        productMonitorLogs.values().stream()
            .sorted(Comparator.comparing(ProductMonitorLogItem::id))
            .limit(overflow)
            .map(ProductMonitorLogItem::id)
            .toList()
            .forEach(productMonitorLogs::remove);
    }

    private String normalizePayMethod(String value) {
        String normalized = normalize(value);
        if ("alipay".equals(normalized) || "wechat".equals(normalized) || "balance".equals(normalized)) {
            return normalized;
        }
        return "wechat";
    }

    private String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("signature calculation failed");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            defaultText(actual, "").toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void appendOpenApiLog(Long userId, String appKey, String path, String status, String message) {
        Long id = openApiLogId.getAndIncrement();
        OpenApiLogItem log = new OpenApiLogItem(
            id,
            userId,
            defaultText(appKey, ""),
            defaultText(path, ""),
            status,
            message,
            OffsetDateTime.now()
        );
        openApiLogs.put(id, log);
        persistOpenApiLog(log);
    }

    private GoodsItem refreshStock(GoodsItem item) {
        if (item.type() != GoodsType.CARD) {
            return item;
        }
        return item.withStock(item.cardKindId() == null ? availableCardCount(item.id()) : availableCardKindCardCount(item.cardKindId()));
    }

    private void refreshGoodsStock(Long targetGoodsId) {
        GoodsItem item = findGoodsSnapshot(targetGoodsId).orElse(null);
        if (item != null && item.type() == GoodsType.CARD) {
            GoodsItem refreshed = refreshStock(item);
            goods.put(item.id(), refreshed);
            persistGoodsSnapshot(refreshed);
        }
    }

    private void refreshGoodsStockForCardKind(Long targetCardKindId) {
        goods.values().stream()
            .filter(item -> Objects.equals(item.cardKindId(), targetCardKindId))
            .forEach(item -> {
                GoodsItem refreshed = refreshStock(item);
                goods.put(item.id(), refreshed);
                persistGoodsSnapshot(refreshed);
            });
    }

    private int availableCardCount(Long targetGoodsId) {
        return (int) cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), targetGoodsId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .count();
    }

    private int availableCardKindCardCount(Long targetCardKindId) {
        return (int) cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), targetCardKindId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .count();
    }

    private boolean containsKeyword(GoodsItem item, String keyword) {
        return normalize(item.goodsName()).contains(keyword)
            || normalize(item.name()).contains(keyword)
            || normalize(item.subTitle()).contains(keyword)
            || normalize(item.description()).contains(keyword)
            || normalize(item.platform()).contains(keyword);
    }

    private Set<Long> categoryTreeIds(Long rootId) {
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
            categoryLevel(item.parentId()) + 1,
            hasChildCategory(item.id())
        );
    }

    private CardKindItem enrichCardKind(CardKindItem item) {
        int total = (int) cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), item.id()))
            .count();
        int available = availableCardKindCardCount(item.id());
        int used = (int) cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), item.id()))
            .filter(card -> "USED".equals(card.status()))
            .count();
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

    private boolean containsOrderKeyword(OrderItem order, String keyword) {
        return normalize(order.orderNo()).contains(keyword)
            || normalize(order.goodsName()).contains(keyword)
            || normalize(order.platform()).contains(keyword)
            || normalize(order.rechargeAccount()).contains(keyword)
            || normalize(order.requestId()).contains(keyword)
            || normalize(order.deliveryMessage()).contains(keyword);
    }

    private List<String> cardLines(CardImportRequest request) {
        List<String> result = new ArrayList<>();
        if (request != null && request.cards() != null) {
            request.cards().forEach(item -> {
                if (item == null) {
                    result.add(null);
                } else {
                    result.addAll(item.lines().toList());
                }
            });
        }
        if (request != null && StringUtils.hasText(request.text())) {
            result.addAll(request.text().lines().toList());
        }
        return result;
    }

    private String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizePlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return List.of("private");
        }
        List<String> normalized = platforms.stream()
            .map(this::normalize)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        return normalized.isEmpty() ? List.of("private") : normalized;
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

    private List<GoodsIntegrationItem> normalizeIntegrations(List<GoodsIntegrationItem> integrations) {
        if (integrations == null || integrations.isEmpty()) {
            return List.of();
        }
        return integrations.stream()
            .filter(Objects::nonNull)
            .map(item -> new GoodsIntegrationItem(
                defaultText(item.id(), UUID.randomUUID().toString()),
                normalize(item.platformCode()),
                defaultText(item.supplierGoodsId(), ""),
                defaultText(item.supplierGoodsName(), ""),
                item.supplierPrice() == null ? BigDecimal.ZERO : item.supplierPrice(),
                defaultText(item.upstreamStatus(), "正常"),
                item.upstreamStock() == null ? 0 : item.upstreamStock(),
                defaultText(item.upstreamTitle(), item.supplierGoodsName()),
                defaultText(item.lastSyncAt(), OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                item.enabled() == null || item.enabled()
            ))
            .filter(item -> StringUtils.hasText(item.platformCode()) || StringUtils.hasText(item.supplierGoodsId()))
            .toList();
    }

    private List<String> normalizeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String normalizeRuleType(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if ("CATEGORY".equals(normalized) || "PLATFORM".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizePermission(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if ("ALLOW".equals(normalized) || "DENY".equals(normalized)) {
            return normalized;
        }
        return "NONE";
    }

    private List<GroupRuleItem> rulesForGroup(Long groupId) {
        Optional<List<GroupRuleItem>> persistent = persistentGroupRules();
        if (persistent.isPresent()) {
            return enrichRuleNames(persistent.get()).stream()
                .filter(rule -> Objects.equals(rule.groupId(), groupId))
                .sorted(Comparator.comparing(GroupRuleItem::ruleType)
                    .thenComparing(rule -> defaultText(rule.targetName(), rule.targetCode())))
                .toList();
        }
        return groupRules.values().stream()
            .filter(rule -> Objects.equals(rule.groupId(), groupId))
            .sorted(Comparator.comparing(GroupRuleItem::ruleType)
                .thenComparing(rule -> defaultText(rule.targetName(), rule.targetCode())))
            .toList();
    }

    private List<GroupRuleItem> enrichRuleNames(List<GroupRuleItem> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
            .map(rule -> {
                if ("CATEGORY".equals(rule.ruleType())) {
                    CategoryItem category = rule.targetId() == null ? null : findCategorySnapshot(rule.targetId()).orElse(null);
                    return new GroupRuleItem(
                        rule.groupId(),
                        rule.ruleType(),
                        rule.targetId(),
                        rule.targetCode(),
                        category == null ? rule.targetName() : category.name(),
                        rule.permission()
                    );
                }
                return new GroupRuleItem(
                    rule.groupId(),
                    rule.ruleType(),
                    rule.targetId(),
                    rule.targetCode(),
                    StringUtils.hasText(rule.targetName()) ? rule.targetName() : platformName(rule.targetCode()),
                    rule.permission()
                );
            })
            .toList();
    }

    private GroupRuleItem createRule(Long groupId, String ruleType, GroupRulePatch patch, String permission) {
        if ("CATEGORY".equals(ruleType)) {
            Long targetId = patch.targetId();
            CategoryItem category = targetId == null ? null : findCategorySnapshot(targetId).orElse(null);
            if (category == null) {
                throw new IllegalArgumentException("category rule target not found");
            }
            return new GroupRuleItem(groupId, ruleType, targetId, null, category.name(), permission);
        }

        String targetCode = normalize(patch.targetCode());
        if (!List.of("h5", "pc", "miniapp").contains(targetCode)) {
            throw new IllegalArgumentException("platform rule target not found");
        }
        return new GroupRuleItem(groupId, ruleType, null, targetCode, platformName(targetCode), permission);
    }

    private String ruleKey(GroupRuleItem rule) {
        String target = "CATEGORY".equals(rule.ruleType()) ? String.valueOf(rule.targetId()) : rule.targetCode();
        return rule.groupId() + ":" + rule.ruleType() + ":" + target;
    }

    private boolean allowedByGroupRules(GoodsItem item, List<GroupRuleItem> rules) {
        if (rules.isEmpty()) {
            return true;
        }
        boolean denied = rules.stream()
            .filter(rule -> "DENY".equals(rule.permission()))
            .anyMatch(rule -> ruleMatches(rule, item));
        if (denied) {
            return false;
        }

        List<GroupRuleItem> allowRules = rules.stream()
            .filter(rule -> "ALLOW".equals(rule.permission()))
            .toList();
        return allowRules.isEmpty() || allowRules.stream().anyMatch(rule -> ruleMatches(rule, item));
    }

    private boolean ruleMatches(GroupRuleItem rule, GoodsItem item) {
        if ("CATEGORY".equals(rule.ruleType())) {
            return rule.targetId() != null && categoryTreeIds(rule.targetId()).contains(item.categoryId());
        }
        if ("PLATFORM".equals(rule.ruleType())) {
            String targetCode = normalize(rule.targetCode());
            return StringUtils.hasText(targetCode) && item.availablePlatforms().stream()
                .map(this::normalize)
                .anyMatch(targetCode::equals);
        }
        return false;
    }

    private void validateOrderPermission(UserItem user) {
        UserGroupItem group = findUserGroupSnapshot(user.groupId() == null ? 1L : user.groupId()).orElse(null);
        if (group == null) {
            return;
        }
        if (!group.orderEnabled()) {
            throw new IllegalStateException("当前会员组暂未开通下单权限，请联系平台客服处理。");
        }
        if (group.realNameRequiredForOrder() && !"VERIFIED".equals(user.verificationStatus())) {
            throw new IllegalStateException("当前会员组需要完成实名认证后才能下单，请先完成实名信息认证。");
        }
    }

    private UserItem withGroupName(UserItem user) {
        return new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            user.balance(),
            user.deposit(),
            user.status(),
            user.createdAt(),
            user.lastLoginAt(),
            user.realNameType(),
            user.realName(),
            user.subjectName(),
            user.certificateNo(),
            user.verificationStatus()
        );
    }

    private UserItem withUserLastLoginAt(UserItem user, OffsetDateTime lastLoginAt) {
        return new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            user.balance(),
            user.deposit(),
            user.status(),
            user.createdAt(),
            lastLoginAt,
            user.realNameType(),
            user.realName(),
            user.subjectName(),
            user.certificateNo(),
            user.verificationStatus()
        );
    }

    private UserItem createUserFromAccount(String account, String code) {
        validateRegistration(account, code);
        Long id = allocateIncrementingId(userId, maxUserId());
        boolean email = account.contains("@");
        Long groupId = validDefaultUserGroupId(systemSetting.defaultUserGroupId());
        UserItem user = new UserItem(
            id,
            "",
            email ? "" : account,
            email ? account : "",
            email ? account.substring(0, account.indexOf("@")) : account,
            groupId,
            groupName(groupId),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "NORMAL",
            OffsetDateTime.now(),
            null,
            "NONE",
            "",
            "",
            "",
            "UNVERIFIED"
        );
        users.put(id, user);
        persistUserSnapshot(user);
        return user;
    }

    private String cleanBearerToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        String trimmed = token.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    private String groupName(Long groupId) {
        UserGroupItem group = findUserGroupSnapshot(groupId).orElse(null);
        return group == null ? "未分组" : group.name();
    }

    private String platformName(String code) {
        return switch (normalize(code)) {
            case "douyin" -> "抖音";
            case "taobao" -> "淘宝";
            case "pdd" -> "拼多多";
            case "xianyu" -> "咸鱼";
            case "xiaohongshu" -> "小红书";
            case "private" -> "私域";
            default -> code;
        };
    }

    private String normalizeRegistrationType(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (List.of("MOBILE", "EMAIL", "FREE").contains(normalized)) {
            return normalized;
        }
        return "MOBILE";
    }

    private Long validDefaultUserGroupId(Long groupId) {
        Long nextGroupId = groupId == null ? 1L : groupId;
        return findUserGroupSnapshot(nextGroupId).isPresent() ? nextGroupId : 1L;
    }

    private void validateRegistration(String account, String code) {
        if (!systemSetting.registrationEnabled()) {
            throw new IllegalStateException("当前系统暂未开放新用户注册");
        }

        String registrationType = normalizeRegistrationType(systemSetting.registrationType());
        if ("MOBILE".equals(registrationType) && !account.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("当前仅支持手机号注册");
        }
        if ("EMAIL".equals(registrationType) && !account.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new IllegalArgumentException("当前仅支持邮箱注册");
        }
        if (!"FREE".equals(registrationType) && !"123456".equals(defaultText(code, "").trim())) {
            throw new IllegalArgumentException("验证码不正确，请输入演示验证码 123456");
        }
    }

    private String normalizeRechargeFieldCode(String value) {
        return normalize(value).replaceAll("[^a-z0-9_]", "_");
    }

    private String normalizeRechargeFieldInputType(String value) {
        String normalized = normalize(value);
        if (List.of("text", "number", "mobile", "email", "textarea", "qq", "jianying_id", "douyin_id").contains(normalized)) {
            return normalized;
        }
        return "text";
    }

    private BigDecimal adjustFundValue(BigDecimal current, BigDecimal amount, String direction, String label) {
        BigDecimal next = "decrease".equals(direction) ? current.subtract(amount) : current.add(amount);
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(label + "不能扣减为负数");
        }
        return next;
    }

    private MemberApiCredentialItem createDefaultMemberCredential(Long userId) {
        MemberApiCredentialItem item = new MemberApiCredentialItem(
            memberCredentials.values().stream().map(MemberApiCredentialItem::id).max(Long::compareTo).orElse(0L) + 1,
            userId,
            memberAppKey(userId),
            memberAppSecret(),
            "DISABLED",
            List.of(),
            1000,
            OffsetDateTime.now(),
            null
        );
        memberCredentials.put(item.appKey(), item);
        return item;
    }

    private String memberAppKey(Long userId) {
        return "member_" + userId;
    }

    private String memberAppSecret() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isMemberApiIpAllowed(MemberApiCredentialItem credential, String clientIp) {
        List<String> whitelist = normalizeTextList(credential.ipWhitelist());
        if (whitelist.isEmpty()) {
            return true;
        }
        String normalizedIp = defaultText(clientIp, "").trim();
        return whitelist.stream().anyMatch(item -> Objects.equals(item, normalizedIp));
    }

    private void validateRechargeAccount(GoodsItem item, String rechargeAccount) {
        if (!Boolean.TRUE.equals(item.requireRechargeAccount())) {
            return;
        }
        String account = defaultText(rechargeAccount, "").trim();
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请先填写充值账号");
        }

        List<RechargeFieldItem> selectedFields = normalizeTextList(item.accountTypes()).stream()
            .map(this::rechargeFieldByCode)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(RechargeFieldItem::enabled)
            .toList();
        if (selectedFields.isEmpty()) {
            return;
        }

        boolean matched = selectedFields.stream().anyMatch(field -> rechargeAccountMatches(field.inputType(), account));
        if (!matched) {
            String labels = selectedFields.stream().map(RechargeFieldItem::label).distinct().reduce((left, right) -> left + " / " + right).orElse("充值账号");
            throw new IllegalArgumentException("请输入正确的" + labels);
        }
    }

    private Optional<RechargeFieldItem> rechargeFieldByCode(String code) {
        String normalizedCode = normalizeRechargeFieldCode(code);
        return rechargeFields.values().stream()
            .filter(item -> Objects.equals(item.code(), normalizedCode))
            .findFirst();
    }

    private boolean rechargeAccountMatches(String inputType, String value) {
        String normalizedType = normalizeRechargeFieldInputType(inputType);
        return switch (normalizedType) {
            case "mobile" -> value.matches("^1[3-9]\\d{9}$");
            case "email" -> value.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
            case "number" -> value.matches("^\\d+$");
            case "qq" -> value.matches("^[1-9]\\d{4,11}$");
            case "jianying_id" -> value.matches("^[A-Za-z0-9_-]{4,32}$");
            case "douyin_id" -> value.matches("^[A-Za-z0-9_.-]{4,32}$");
            default -> StringUtils.hasText(value);
        };
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

    private boolean booleanSetting(Map<String, String> settings, String key, boolean fallback) {
        String value = settings.get(key);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private int intSetting(Map<String, String> settings, String key, int fallback, int minimum) {
        try {
            return Math.max(minimum, Integer.parseInt(defaultText(settings.get(key), String.valueOf(fallback))));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long longSetting(Map<String, String> settings, String key, Long fallback) {
        try {
            return Long.parseLong(defaultText(settings.get(key), String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
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

    private void seedCategories() {
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

    private void seedRechargeFields() {
        OffsetDateTime now = OffsetDateTime.now();
        rechargeFields.put(1L, new RechargeFieldItem(1L, "mobile", "手机号", "请输入充值手机号", "用于手机号直充、会员绑定等商品", "mobile", true, 10, true, now, now));
        rechargeFields.put(2L, new RechargeFieldItem(2L, "qq", "QQ号", "请输入 QQ 号", "用于 QQ 会员、黄钻等权益商品", "qq", true, 20, true, now, now));
        rechargeFields.put(3L, new RechargeFieldItem(3L, "wechat", "微信号", "请输入微信号", "用于微信生态权益或人工核验", "text", false, 30, true, now, now));
        rechargeFields.put(4L, new RechargeFieldItem(4L, "game_uid", "游戏 UID", "请输入游戏 UID", "用于游戏点券、区服角色类商品", "text", true, 40, true, now, now));
        rechargeFields.put(5L, new RechargeFieldItem(5L, "email", "邮箱", "请输入邮箱", "用于邮箱登录或海外账号类商品", "email", false, 50, true, now, now));
        rechargeFields.put(6L, new RechargeFieldItem(6L, "jianying_id", "剪映ID", "请输入剪映 ID", "用于剪映相关权益或模板服务", "jianying_id", true, 60, true, now, now));
        rechargeFields.put(7L, new RechargeFieldItem(7L, "douyin_id", "抖音ID", "请输入抖音 ID", "用于抖音账号权益或投流服务", "douyin_id", true, 70, true, now, now));
    }

    private void seedUserGroups() {
        userGroups.put(1L, new UserGroupItem(1L, "默认会员", "注册后自动归入的基础用户组", true, 0, "ENABLED", true, false, List.of()));
        userGroups.put(2L, new UserGroupItem(2L, "渠道 VIP", "仅开放私域，屏蔽人工代充类目", false, 0, "ENABLED", true, true, List.of()));
        userGroups.put(3L, new UserGroupItem(3L, "受限会员", "风控观察组，限制游戏直充和淘宝平台购买", false, 0, "ENABLED", false, false, List.of()));

        groupRules.put("2:CATEGORY:333", new GroupRuleItem(2L, "CATEGORY", 333L, null, "人工处理", "DENY"));
        groupRules.put("2:PLATFORM:private", new GroupRuleItem(2L, "PLATFORM", null, "private", "私域", "ALLOW"));
        groupRules.put("3:CATEGORY:2", new GroupRuleItem(3L, "CATEGORY", 2L, null, "游戏直充", "DENY"));
        groupRules.put("3:PLATFORM:taobao", new GroupRuleItem(3L, "PLATFORM", null, "taobao", "淘宝", "DENY"));
    }

    private void seedUsers() {
        OffsetDateTime now = OffsetDateTime.now();
        users.put(90001L, new UserItem(
            90001L,
            "",
            "13800000001",
            "alpha@example.com",
            "Alpha 买家",
            1L,
            groupName(1L),
            BigDecimal.valueOf(128.66),
            BigDecimal.valueOf(300.00),
            "NORMAL",
            now.minusDays(8),
            now.minusHours(6),
            "PERSONAL",
            "张明",
            "",
            "110101********1234",
            "VERIFIED"
        ));
        users.put(90002L, new UserItem(
            90002L,
            "",
            "13800000002",
            "vip@example.com",
            "渠道 VIP",
            2L,
            groupName(2L),
            BigDecimal.valueOf(888.00),
            BigDecimal.valueOf(1200.00),
            "NORMAL",
            now.minusDays(3),
            now.minusHours(2),
            "SUBJECT",
            "李华",
            "星河渠道服务部",
            "913101********5678",
            "VERIFIED"
        ));
        users.put(90003L, new UserItem(
            90003L,
            "",
            "13800000003",
            "risk@example.com",
            "受限会员",
            3L,
            groupName(3L),
            BigDecimal.valueOf(12.30),
            BigDecimal.valueOf(50.00),
            "FROZEN",
            now.minusDays(1),
            null,
            "NONE",
            "",
            "",
            "",
            "UNVERIFIED"
        ));
    }

    private void seedMemberCredentials() {
        memberCredentials.put("demo_app_key", new MemberApiCredentialItem(
            1L,
            90002L,
            "demo_app_key",
            "demo_app_secret",
            "ENABLED",
            List.of(),
            1000,
            OffsetDateTime.now(),
            null
        ));
    }

    private void seedSuppliers() {
        OffsetDateTime now = OffsetDateTime.now();
        suppliers.put(20001L, new SupplierItem(
            20001L,
            "星河直充",
            "CUSTOM",
            "https://api.starcharge.example",
            "star-demo-key",
            "star****cret",
            "",
            "star-demo-key",
            "",
            "",
            10,
            BigDecimal.valueOf(2688.50),
            "ENABLED",
            "默认优先供应商，支持余额查询和模拟连接测试",
            now
        ));
        suppliers.put(20002L, new SupplierItem(
            20002L,
            "云桥货源",
            "CUSTOM",
            "https://api.bridge.example",
            "bridge-demo-key",
            "brid****cret",
            "",
            "bridge-demo-key",
            "",
            "",
            10,
            BigDecimal.valueOf(188.20),
            "ENABLED",
            "低余额示例，用于仪表盘告警",
            now
        ));
    }

    private void seedGoodsChannels() {
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
        if (parentId == null || parentId == 0L) {
            return 0;
        }
        CategoryItem parent = findCategorySnapshot(parentId).orElse(null);
        if (parent == null) {
            return 0;
        }
        return categoryLevel(parent.parentId()) + 1;
    }

    private void seedGoods() {
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
            "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "下单完成后自动出卡，订单详情页可查看卡密与使用说明。")),
            List.of(new GoodsIntegrationItem("link-10001-1", "douyin", "DY-VIP-WEEK", "抖音视频会员周卡", BigDecimal.valueOf(5.80), "正常", 120, "视频会员周卡", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)),
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
            List.of("douyin", "taobao", "private"),
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
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "直充商品会按渠道优先级自动采购，失败后自动切换备用渠道。")),
            List.of(
                new GoodsIntegrationItem("link-10002-1", "taobao", "TB-GAME-60", "淘宝游戏点券 60 枚", BigDecimal.valueOf(5.20), "正常", 999, "游戏点券 60 枚", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true),
                new GoodsIntegrationItem("link-10002-2", "pdd", "PDD-GAME-60", "拼多多点券 60 枚", BigDecimal.valueOf(5.10), "正常", 860, "游戏点券 60 枚", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)
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
            List.of("taobao", "pdd", "xianyu"),
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
            "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "代充订单由后台人工确认完成，适合需要客服处理的服务商品。")),
            List.of(new GoodsIntegrationItem("link-10003-1", "private", "PR-MANUAL-001", "私域人工代办", BigDecimal.valueOf(16.00), "正常", 50, "资料人工代办服务", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), true)),
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
            List.of("xiaohongshu", "private"),
            List.of("douyin"),
            null
        ));
    }

    private void seedOrders() {
        OffsetDateTime now = OffsetDateTime.now();
        orders.put("DEMO-CARD-001", new OrderItem(
            "DEMO-CARD-001",
            90001L,
            "13800000001",
            10001L,
            "视频会员周卡",
            GoodsType.CARD,
            "douyin",
            1,
            BigDecimal.valueOf(6.90),
            BigDecimal.valueOf(6.90),
            OrderStatus.DELIVERED,
            "",
            "演示卡密订单",
            "seed-card",
            "PAY-DEMO-CARD",
            "alipay",
            List.of("VIP-7D-DEMO----A1B2"),
            List.of(),
            "卡密已自动发放，可在订单详情查看。",
            now.minusMinutes(36),
            now.minusMinutes(35),
            now.minusMinutes(35)
        ));
        orders.put("DEMO-DIRECT-001", new OrderItem(
            "DEMO-DIRECT-001",
            90002L,
            "13800000002",
            10002L,
            "游戏点券 60 枚",
            GoodsType.DIRECT,
            "taobao",
            1,
            BigDecimal.valueOf(5.80),
            BigDecimal.valueOf(5.80),
            OrderStatus.PROCURING,
            "13800000002",
            "演示直充订单",
            "seed-direct",
            "PAY-DEMO-DIRECT",
            "wechat",
            List.of(),
            List.of(new ChannelAttemptItem(
                30001L,
                20001L,
                "星河直充",
                "STAR-GAME-60",
                10,
                "SUBMITTED",
                "已提交上游，等待回调",
                now.minusMinutes(18)
            )),
            "直充采购中，正在等待上游返回充值结果。",
            now.minusMinutes(20),
            now.minusMinutes(19),
            null
        ));
        orders.put("DEMO-MANUAL-001", new OrderItem(
            "DEMO-MANUAL-001",
            90003L,
            "13800000003",
            10003L,
            "资料人工代办服务",
            GoodsType.MANUAL,
            "private",
            1,
            BigDecimal.valueOf(19.90),
            BigDecimal.valueOf(19.90),
            OrderStatus.WAITING_MANUAL,
            "wechat_demo_001",
            "演示代充订单",
            "seed-manual",
            "PAY-DEMO-MANUAL",
            "alipay",
            List.of(),
            List.of(),
            "等待管理员人工处理。",
            now.minusMinutes(12),
            now.minusMinutes(11),
            null
        ));
    }
}
