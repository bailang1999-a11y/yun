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
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryShopRepository {
    private static final Charset GBK_CHARSET = Charset.forName("GBK");
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
    private static final String PAYMENT_CHANNEL_SETTING_KEY = "payment.channels";
    private static final String PRICE_TEMPLATE_SETTING_KEY = "price.templates";
    private static final String ADMIN_STAFF_SETTING_KEY = "admin.staff.accounts";
    private static final String SUPER_ADMIN_USERNAME_KEY = "admin.super.username";
    private static final String SUPER_ADMIN_PASSWORD_KEY = "admin.super.passwordHash";
    private static final String SUPER_ADMIN_NICKNAME_KEY = "admin.super.nickname";
    private static final List<String> ALL_ADMIN_PERMISSIONS = List.of(
        "dashboard:read",
        "goods:manage",
        "orders:manage",
        "users:manage",
        "settings:manage",
        "staff:manage"
    );
    private static final int KASUSHOU_CATEGORY_ENRICH_LIMIT = 100;
    private static final int KASUSHOU_CATEGORY_ENRICH_MAX_PAGES = 2;
    private static final int KASUSHOU_CATEGORY_ENRICH_MAX_REQUESTS = 120;
    private static final Pattern PRICE_LIMIT_PATTERN = Pattern.compile("(?:限价|限制售价|限定价格|控价)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*(?:元|块|rmb|RMB|¥)?)");
    private static final DateTimeFormatter ORDER_NO_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter FULU_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SALES_TERMINAL_PLATFORMS = Set.of("all", "h5", "web", "pc", "api", "private");
    private static final Set<String> LEGACY_SYSTEM_GOODS_TAGS = Set.of("new", "api-source");
    private static final String SMS_LOGIN_SETTING_KEY = "sms.login.setting";
    private static final String CAPTCHA_SETTING_KEY = "captcha.setting";
    private static final String DEFAULT_PRICE_LIMIT_NOTICE = "当前会员组暂未开放限价商品购买权限，请联系平台客服处理。";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<Long, CategoryItem> categories = new ConcurrentHashMap<>();
    private final Map<Long, CardKindItem> cardKinds = new ConcurrentHashMap<>();
    private final Map<Long, GoodsItem> goods = new ConcurrentHashMap<>();
    private final Map<Long, CardSecret> cards = new ConcurrentHashMap<>();
    private final Map<String, OrderItem> orders = new ConcurrentHashMap<>();
    private final Map<String, PaymentItem> payments = new ConcurrentHashMap<>();
    private final Map<Long, PaymentCallbackLogItem> paymentCallbackLogs = new ConcurrentHashMap<>();
    private final Map<String, RefundItem> refunds = new ConcurrentHashMap<>();
    private final Map<Long, SmsLogItem> smsLogs = new ConcurrentHashMap<>();
    private final Map<String, SmsVerificationCode> smsVerificationCodes = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> sliderTokens = new ConcurrentHashMap<>();
    private final Map<Long, OperationLogItem> operationLogs = new ConcurrentHashMap<>();
    private final Map<Long, SupplierItem> suppliers = new ConcurrentHashMap<>();
    private final Map<Long, String> supplierApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, RemoteGoodsSyncResult> remoteGoodsSyncResults = new ConcurrentHashMap<>();
    private final Map<Long, GoodsChannelItem> goodsChannels = new ConcurrentHashMap<>();
    private final Map<Long, ProductMonitorState> productMonitorStates = new ConcurrentHashMap<>();
    private final Map<Long, ProductMonitorLogItem> productMonitorLogs = new ConcurrentHashMap<>();
    private final Map<Long, RechargeFieldItem> rechargeFields = new ConcurrentHashMap<>();
    private final Map<Long, PaymentChannelItem> paymentChannels = new ConcurrentHashMap<>();
    private final List<PriceTemplateItem> priceTemplates = new ArrayList<>();
    private final Map<Long, UserGroupItem> userGroups = new ConcurrentHashMap<>();
    private final Map<Long, UserItem> users = new ConcurrentHashMap<>();
    private final Map<String, MemberApiCredentialItem> memberCredentials = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> memberNonceExpiresAt = new ConcurrentHashMap<>();
    private final Map<Long, OpenApiLogItem> openApiLogs = new ConcurrentHashMap<>();
    private final Map<String, GroupRuleItem> groupRules = new ConcurrentHashMap<>();
    private final Map<Long, String> userPasswordHashes = new ConcurrentHashMap<>();
    private final Map<String, Long> userTokens = new ConcurrentHashMap<>();
    private final Map<String, AdminProfile> adminTokens = new ConcurrentHashMap<>();
    private final Map<Long, AdminStaffItem> adminStaff = new ConcurrentHashMap<>();
    private final Map<Long, String> adminStaffPasswordHashes = new ConcurrentHashMap<>();
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
    private volatile SmsLoginSettingItem smsLoginSetting = new SmsLoginSettingItem(
        false,
        false,
        false,
        false,
        "TENCENT",
        "",
        6,
        300,
        60,
        5,
        Map.of(),
        Map.of(
            "secret_id", "",
            "secret_key", "",
            "sdk_app_id", "",
            "sign_name", "",
            "template_id", "",
            "region", "ap-guangzhou",
            "template_param_json", "[\"{code}\"]"
        ),
        Map.of(
            "access_key_id", "",
            "access_key_secret", "",
            "sign_name", "",
            "template_code", "",
            "region", "cn-hangzhou",
            "template_param_json", "{\"code\":\"{code}\"}"
        )
    );
    private volatile CaptchaSettingItem captchaSetting = new CaptchaSettingItem(
        false,
        false,
        false,
        false,
        "TENCENT",
        Map.of(
            "secret_id", "",
            "secret_key", "",
            "captcha_app_id", "",
            "app_secret_key", "",
            "region", "ap-guangzhou",
            "scene", "login"
        ),
        Map.of(
            "site_key", "",
            "secret_key", "",
            "scene", "login"
        ),
        Map.of()
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
    private final AtomicLong paymentChannelId = new AtomicLong(5);
    private final AtomicLong userId = new AtomicLong(90003);
    private final AtomicLong adminStaffId = new AtomicLong(1000);
    private final AtomicLong openApiLogId = new AtomicLong(1);
    private final OrderRealtimeBroadcaster realtimeBroadcaster;
    private final PersistentOrderStore persistentOrderStore;
    private final CatalogPersistenceStore catalogPersistenceStore;
    private final AuditPersistenceStore auditPersistenceStore;
    private final ConfigPersistenceStore configPersistenceStore;
    private final RedisSecurityStateStore securityStateStore;
    private final boolean prodProfile;
    private volatile String adminUsername;
    private volatile String adminPasswordBcrypt;
    private volatile String adminNickname;

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
            (RedisSecurityStateStore) null,
            false,
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
        ObjectProvider<RedisSecurityStateStore> securityStateStoreProvider,
        Environment environment,
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
            securityStateStoreProvider.getIfAvailable(),
            isProdProfile(environment),
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
        RedisSecurityStateStore securityStateStore,
        boolean prodProfile,
        String adminUsername,
        String adminPasswordBcrypt,
        String adminNickname
    ) {
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.persistentOrderStore = persistentOrderStore;
        this.catalogPersistenceStore = catalogPersistenceStore;
        this.auditPersistenceStore = auditPersistenceStore;
        this.configPersistenceStore = configPersistenceStore;
        this.securityStateStore = securityStateStore;
        this.prodProfile = prodProfile;
        this.adminUsername = normalize(defaultText(adminUsername, "admin"));
        this.adminPasswordBcrypt = defaultText(adminPasswordBcrypt, DEFAULT_ADMIN_PASSWORD_BCRYPT);
        this.adminNickname = defaultText(adminNickname, "运营管理员");
        loadPersistentSystemSetting();
        loadSuperAdminCredentials();
        loadSmsLoginSetting();
        loadCaptchaSetting();
        loadAdminStaff();
        loadPaymentChannels();
        loadPriceTemplates();
        if (prodProfile && !fullPersistenceEnabled()) {
            throw new IllegalStateException("prod profile requires order, catalog, audit and config persistence stores");
        }
        if (persistenceEnabled()) {
            return;
        }
        seedCategories();
        seedRechargeFields();
        seedPaymentChannels(false);
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

    private boolean persistenceEnabled() {
        return persistentOrderStore != null
            || catalogPersistenceStore != null
            || auditPersistenceStore != null
            || configPersistenceStore != null;
    }

    private boolean fullPersistenceEnabled() {
        return persistentOrderStore != null
            && catalogPersistenceStore != null
            && auditPersistenceStore != null
            && configPersistenceStore != null;
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null
            && List.of(environment.getActiveProfiles()).stream().anyMatch("prod"::equalsIgnoreCase);
    }

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
        return source.stream()
            .filter(item -> admin || "ON_SALE".equals(item.status()))
            .filter(item -> categoryId == null || categoryScope.contains(item.categoryId()))
            .filter(item -> admin || !StringUtils.hasText(normalizedPlatform) || goodsAllowsPlatform(item, normalizedPlatform))
            .filter(item -> admin || allowedByGroupRules(item, activeRules))
            .filter(item -> !StringUtils.hasText(keyword) || containsKeyword(item, keyword))
            .map(item -> refresh ? refreshStock(item) : item)
            .map(this::withChannelIntegrations)
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    public Optional<GoodsItem> findGoods(Long id) {
        return findGoodsSnapshot(id).map(this::refreshStock);
    }

    public Optional<GoodsItem> findGoods(Long id, Long userGroupId, boolean admin) {
        return findGoods(id)
            .filter(item -> admin || "ON_SALE".equals(item.status()));
    }

    public SystemSettingItem systemSetting() {
        return systemSetting;
    }

    public SmsLoginSettingItem smsLoginSetting() {
        return smsLoginSetting;
    }

    public CaptchaSettingItem captchaSetting() {
        return captchaSetting;
    }

    public CaptchaChallengeItem captchaChallenge(String terminal) {
        String cleanTerminal = normalizeTerminal(terminal);
        boolean required = isCaptchaRequired(cleanTerminal);
        String provider = normalizeCaptchaProvider(captchaSetting.provider());
        Map<String, String> config = "TURNSTILE".equals(provider) ? captchaSetting.turnstileConfig() : captchaSetting.tencentConfig();
        return new CaptchaChallengeItem(
            required,
            provider,
            required ? defaultText("TURNSTILE".equals(provider) ? config.get("site_key") : config.get("captcha_app_id"), "") : "",
            defaultText(config.get("scene"), "login")
        );
    }

    public synchronized SmsLoginSettingItem updateSmsLoginSetting(SmsLoginSettingRequest request) {
        if (request == null) {
            return smsLoginSetting;
        }
        smsLoginSetting = new SmsLoginSettingItem(
            request.enabled() == null ? smsLoginSetting.enabled() : request.enabled(),
            request.adminLoginEnabled() == null ? smsLoginSetting.adminLoginEnabled() : request.adminLoginEnabled(),
            request.h5LoginEnabled() == null ? smsLoginSetting.h5LoginEnabled() : request.h5LoginEnabled(),
            request.webLoginEnabled() == null ? smsLoginSetting.webLoginEnabled() : request.webLoginEnabled(),
            normalizeSmsProvider(defaultText(request.provider(), smsLoginSetting.provider())),
            defaultText(request.adminMobile(), smsLoginSetting.adminMobile()).trim(),
            clampInt(request.codeLength() == null ? smsLoginSetting.codeLength() : request.codeLength(), 4, 8),
            clampInt(request.ttlSeconds() == null ? smsLoginSetting.ttlSeconds() : request.ttlSeconds(), 60, 1800),
            clampInt(request.cooldownSeconds() == null ? smsLoginSetting.cooldownSeconds() : request.cooldownSeconds(), 10, 300),
            clampInt(request.maxAttempts() == null ? smsLoginSetting.maxAttempts() : request.maxAttempts(), 1, 10),
            normalizeSmsConfig(request.genericConfig() == null ? smsLoginSetting.genericConfig() : request.genericConfig()),
            normalizeSmsConfig(request.tencentConfig() == null ? smsLoginSetting.tencentConfig() : request.tencentConfig()),
            normalizeSmsConfig(request.aliyunConfig() == null ? smsLoginSetting.aliyunConfig() : request.aliyunConfig())
        );
        persistSmsLoginSetting();
        return smsLoginSetting;
    }

    public synchronized CaptchaSettingItem updateCaptchaSetting(CaptchaSettingRequest request) {
        if (request == null) {
            return captchaSetting;
        }
        CaptchaSettingItem next = captchaSettingFromRequest(request);
        validateCaptchaSetting(next);
        captchaSetting = next;
        persistCaptchaSetting();
        return captchaSetting;
    }

    public String testCaptchaSetting(CaptchaSettingRequest request) {
        CaptchaSettingItem setting = request == null ? captchaSetting : captchaSettingFromRequest(request);
        validateCaptchaSetting(setting);
        if (!setting.enabled()) {
            return "人机验证总开关未开启，当前不会触发校验。";
        }
        return switch (normalizeCaptchaProvider(setting.provider())) {
            case "GENERIC" -> testGenericCaptchaSetting(setting.genericConfig());
            case "TURNSTILE" -> testTurnstileCaptchaSetting(setting.turnstileConfig());
            default -> testTencentCaptchaSetting(setting.tencentConfig());
        };
    }

    private CaptchaSettingItem captchaSettingFromRequest(CaptchaSettingRequest request) {
        return new CaptchaSettingItem(
            request.enabled() == null ? captchaSetting.enabled() : request.enabled(),
            request.adminLoginEnabled() == null ? captchaSetting.adminLoginEnabled() : request.adminLoginEnabled(),
            request.h5LoginEnabled() == null ? captchaSetting.h5LoginEnabled() : request.h5LoginEnabled(),
            request.webLoginEnabled() == null ? captchaSetting.webLoginEnabled() : request.webLoginEnabled(),
            normalizeCaptchaProvider(defaultText(request.provider(), captchaSetting.provider())),
            normalizeSmsConfig(request.tencentConfig() == null ? captchaSetting.tencentConfig() : request.tencentConfig()),
            normalizeSmsConfig(request.turnstileConfig() == null ? captchaSetting.turnstileConfig() : request.turnstileConfig()),
            normalizeSmsConfig(request.genericConfig() == null ? captchaSetting.genericConfig() : request.genericConfig())
        );
    }

    private void validateCaptchaSetting(CaptchaSettingItem setting) {
        if (setting == null || !setting.enabled()) {
            return;
        }
        if (!setting.adminLoginEnabled() && !setting.h5LoginEnabled() && !setting.webLoginEnabled()) {
            return;
        }
        String provider = normalizeCaptchaProvider(setting.provider());
        if ("GENERIC".equals(provider)) {
            requireConfig(setting.genericConfig(), "url", "通用 HTTP 校验请求地址");
            return;
        }
        if ("TURNSTILE".equals(provider)) {
            requireConfig(setting.turnstileConfig(), "site_key", "Cloudflare Turnstile Site Key");
            requireConfig(setting.turnstileConfig(), "secret_key", "Cloudflare Turnstile Secret Key");
            return;
        }
        Map<String, String> config = setting.tencentConfig();
        requireConfig(config, "secret_id", "腾讯云 SecretId");
        requireConfig(config, "secret_key", "腾讯云 SecretKey");
        requireConfig(config, "captcha_app_id", "腾讯云 CaptchaAppId");
        requireConfig(config, "app_secret_key", "腾讯云 AppSecretKey");
    }

    private void requireConfig(Map<String, String> config, String key, String label) {
        if (!StringUtils.hasText(defaultText(config == null ? "" : config.get(key), ""))) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }

    public synchronized String sendAdminLoginSmsCode(SendSmsCodeRequest request) {
        return sendAdminLoginSmsCode(request, "");
    }

    public synchronized String sendAdminLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
        verifyHumanCaptchaIfRequired("admin", request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        if (!smsLoginSetting.enabled() || !smsLoginSetting.adminLoginEnabled()) {
            throw new IllegalStateException("后台短信验证登录未启用");
        }
        String mobile = defaultText(smsLoginSetting.adminMobile(), "").trim();
        if (!isMobile(mobile)) {
            throw new IllegalStateException("请先在后台配置管理员接收验证码手机号");
        }
        return sendLoginSmsCode("admin", mobile, "ADMIN_LOGIN");
    }

    public synchronized String sendUserLoginSmsCode(SendSmsCodeRequest request) {
        return sendUserLoginSmsCode(request, "");
    }

    public synchronized String sendUserLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
        String terminal = normalizeTerminal(request == null ? "" : request.terminal());
        String mode = normalize(defaultText(request == null ? "" : request.mode(), "login"));
        verifyHumanCaptchaIfRequired(terminal, request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        String mobile = normalize(request == null ? "" : request.account());
        if ("register".equals(mode)) {
            validateRegistration(mobile);
            if (!isRegistrationSmsCodeRequired()) {
                throw new IllegalStateException("当前注册方式不需要短信验证码");
            }
            if (!smsLoginSetting.enabled()) {
                throw new IllegalStateException("短信验证码服务未启用");
            }
        } else if ("forgot".equals(mode)) {
            if (!smsLoginSetting.enabled()) {
                throw new IllegalStateException("短信验证码服务未启用");
            }
        } else if (!isUserSmsLoginRequired(terminal)) {
            throw new IllegalStateException("当前端未启用短信验证登录");
        }
        if (!isMobile(mobile)) {
            throw new IllegalArgumentException("请输入正确的手机号");
        }
        return sendLoginSmsCode(terminal, mobile, "USER_LOGIN");
    }

    public synchronized String createSliderToken(String terminal) {
        String token = "slider_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(5);
        sliderTokens.put(token, expiresAt);
        if (securityStateStore != null) {
            securityStateStore.storeSliderToken(token, Duration.between(OffsetDateTime.now(), expiresAt));
        }
        return token;
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

    public synchronized List<PriceTemplateItem> listPriceTemplates() {
        ensurePriceTemplatesReady();
        return priceTemplates.stream()
            .map(this::sanitizePriceTemplate)
            .toList();
    }

    public synchronized List<PriceTemplateItem> savePriceTemplates(List<PriceTemplateItem> request) {
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
        persistPriceTemplates();
        return listPriceTemplates();
    }

    public synchronized AuthSession<UserItem> loginUser(LoginRequest request) {
        return loginUser(request, "");
    }

    public synchronized AuthSession<UserItem> loginUser(LoginRequest request, String clientIp) {
        return loginUser(request, clientIp, true);
    }

    private AuthSession<UserItem> loginUser(LoginRequest request, String clientIp, boolean verifyCaptcha) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("account is required");
        }
        String terminal = normalizeTerminal(request == null ? "" : request.terminal());
        if (verifyCaptcha) {
            verifyHumanCaptchaIfRequired(terminal, request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        }
        boolean smsRequired = isUserSmsLoginRequired(terminal);
        Optional<UserItem> matchedUser = allUserSnapshots().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))
            .findFirst();
        boolean hasPassword = StringUtils.hasText(defaultText(request == null ? "" : request.password(), ""));
        if (smsRequired) {
            verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        }
        UserItem user = matchedUser.orElseThrow(() -> new IllegalArgumentException("账号不存在，请先注册"));
        if (!smsRequired || hasPassword) {
            verifyUserPassword(user, request);
        }
        UserItem next = withUserLastLoginAt(user, OffsetDateTime.now());
        users.put(next.id(), next);
        persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        return new AuthSession<>(token, withGroupName(next));
    }

    public synchronized AuthSession<UserItem> authenticateUser(UserAuthRequest request) {
        return authenticateUser(request, "");
    }

    public synchronized AuthSession<UserItem> authenticateUser(UserAuthRequest request, String clientIp) {
        String mode = normalize(defaultText(request == null ? "" : request.mode(), "login"));
        String terminal = normalizeTerminal(request == null ? "" : request.terminal());
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请输入账号");
        }
        verifyHumanCaptchaIfRequired(terminal, request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        return switch (mode) {
            case "register" -> registerUser(request, terminal, account);
            case "forgot" -> resetUserPassword(request, terminal, account);
            default -> loginUser(new LoginRequest(account, request == null ? "" : request.password(), request == null ? "" : request.code(), terminal, "", request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr()), clientIp, false);
        };
    }

    public synchronized UserItem changeUserPassword(Long userId, PasswordChangeRequest request) {
        UserItem user = requiredUser(userId);
        String currentPassword = defaultText(request == null ? "" : request.currentPassword(), "");
        String newPassword = defaultText(request == null ? "" : request.newPassword(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (!StringUtils.hasText(newPassword) || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码至少需要 6 位");
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
        String currentHash = userPasswordHashes.get(userId);
        if (StringUtils.hasText(currentHash) && !ADMIN_PASSWORD_ENCODER.matches(currentPassword, currentHash)) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        String nextHash = ADMIN_PASSWORD_ENCODER.encode(newPassword);
        userPasswordHashes.put(userId, nextHash);
        persistRuntimeSetting("user.password." + userId, nextHash);
        invalidateUserTokens(userId);
        appendOperation("USER_PASSWORD_CHANGE", "USER", String.valueOf(userId), "会员修改登录密码");
        return withGroupName(user);
    }

    public synchronized RechargeRequestResult createRechargeRequest(Long userId, RechargeRequest request) {
        if (prodProfile) {
            throw new IllegalStateException("生产环境不允许模拟充值，请接入真实支付网关后再开放充值");
        }
        UserItem user = requiredUser(userId);
        BigDecimal amount = request == null || request.amount() == null ? BigDecimal.ZERO : request.amount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0");
        }
        String method = normalizePayMethod(request == null ? "" : request.payMethod());
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal currentBalance = user.balance() == null ? BigDecimal.ZERO : user.balance();
        UserItem next = new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            currentBalance.add(amount),
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
        String requestNo = "RCH" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", paymentSeq.getAndIncrement());
        appendOperation("USER_RECHARGE_SUCCESS", "USER", String.valueOf(userId), method + ":" + amount.toPlainString() + ":" + defaultText(request == null ? "" : request.remark(), ""));
        return new RechargeRequestResult(requestNo, amount, method, "SUCCESS", now, withGroupName(next));
    }

    public Optional<UserItem> findUserByToken(String token) {
        String cleanToken = cleanBearerToken(token);
        if (StringUtils.hasText(cleanToken) && securityStateStore != null) {
            Optional<Long> redisUserId = securityStateStore.loadUserToken(cleanToken);
            if (redisUserId.isPresent()) {
                return findUserSnapshot(redisUserId.get()).map(this::withGroupName);
            }
        }
        if (isTokenExpired(cleanToken, userTokenExpiresAt)) {
            userTokens.remove(cleanToken);
            userTokenExpiresAt.remove(cleanToken);
            if (securityStateStore != null) {
                securityStateStore.deleteUserToken(cleanToken);
            }
            return Optional.empty();
        }
        Long userId = userTokens.get(cleanToken);
        return userId == null ? Optional.empty() : findUserSnapshot(userId).map(this::withGroupName);
    }

    public void logoutUser(String token) {
        String cleanToken = cleanBearerToken(token);
        if (!StringUtils.hasText(cleanToken)) {
            return;
        }
        userTokens.remove(cleanToken);
        userTokenExpiresAt.remove(cleanToken);
        if (securityStateStore != null) {
            securityStateStore.deleteUserToken(cleanToken);
        }
    }

    public AuthSession<AdminProfile> loginAdmin(LoginRequest request) {
        return loginAdmin(request, "");
    }

    public AuthSession<AdminProfile> loginAdmin(LoginRequest request, String clientIp) {
        String account = normalize(request == null ? "" : request.account());
        String password = request == null ? "" : request.password();
        verifyHumanCaptchaIfRequired("admin", request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        if (Objects.equals(adminUsername, account)) {
            if (!ADMIN_PASSWORD_ENCODER.matches(password, adminPasswordBcrypt)) {
                throw new IllegalArgumentException("admin account or password is invalid");
            }
            if (smsLoginSetting.enabled() && smsLoginSetting.adminLoginEnabled()) {
                String mobile = defaultText(smsLoginSetting.adminMobile(), "").trim();
                if (!isMobile(mobile)) {
                    throw new IllegalStateException("管理员短信验证登录已开启，但未配置管理员手机号");
                }
                verifyLoginSmsCode(verificationKey("ADMIN_LOGIN", "admin", mobile), request == null ? "" : request.code());
            }
            AdminProfile profile = new AdminProfile(
                1L,
                adminUsername,
                adminNickname,
                ALL_ADMIN_PERMISSIONS
            );
            return issueAdminSession(profile);
        }

        AdminStaffItem staff = adminStaff.values().stream()
            .filter(item -> Objects.equals(normalize(item.account()), account))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("admin account or password is invalid"));
        if (!"ENABLED".equalsIgnoreCase(defaultText(staff.status(), ""))) {
            throw new IllegalStateException("员工账号已停用，请联系超级管理员");
        }
        String hash = adminStaffPasswordHashes.get(staff.id());
        if (!StringUtils.hasText(hash) || !ADMIN_PASSWORD_ENCODER.matches(password, hash)) {
            throw new IllegalArgumentException("admin account or password is invalid");
        }
        if (smsLoginSetting.enabled() && smsLoginSetting.adminLoginEnabled()) {
            String mobile = defaultText(smsLoginSetting.adminMobile(), "").trim();
            if (!isMobile(mobile)) {
                throw new IllegalStateException("管理员短信验证登录已开启，但未配置管理员手机号");
            }
            verifyLoginSmsCode(verificationKey("ADMIN_LOGIN", "admin", mobile), request == null ? "" : request.code());
        }
        AdminProfile profile = new AdminProfile(
            staff.id(),
            staff.account(),
            staff.nickname(),
            staff.permissions()
        );
        return issueAdminSession(profile);
    }

    private AuthSession<AdminProfile> issueAdminSession(AdminProfile profile) {
        String token = "admin_" + UUID.randomUUID();
        adminTokens.put(token, profile);
        adminTokenExpiresAt.put(token, OffsetDateTime.now().plus(ADMIN_TOKEN_TTL));
        if (securityStateStore != null) {
            securityStateStore.storeAdminToken(token, profile, ADMIN_TOKEN_TTL);
        }
        return new AuthSession<>(token, profile);
    }

    public Optional<AdminProfile> findAdminByToken(String token) {
        String cleanToken = cleanBearerToken(token);
        if (StringUtils.hasText(cleanToken) && securityStateStore != null) {
            Optional<AdminProfile> redisProfile = securityStateStore.loadAdminToken(cleanToken);
            if (redisProfile.isPresent()) {
                Optional<AdminProfile> activeProfile = activeAdminProfile(redisProfile.get());
                if (activeProfile.isEmpty()) {
                    securityStateStore.deleteAdminToken(cleanToken);
                }
                return activeProfile;
            }
        }
        if (isTokenExpired(cleanToken, adminTokenExpiresAt)) {
            adminTokens.remove(cleanToken);
            adminTokenExpiresAt.remove(cleanToken);
            return Optional.empty();
        }
        Optional<AdminProfile> activeProfile = Optional.ofNullable(adminTokens.get(cleanToken)).flatMap(this::activeAdminProfile);
        if (activeProfile.isEmpty()) {
            adminTokens.remove(cleanToken);
            adminTokenExpiresAt.remove(cleanToken);
        } else {
            adminTokens.put(cleanToken, activeProfile.get());
        }
        return activeProfile;
    }

    private Optional<AdminProfile> activeAdminProfile(AdminProfile profile) {
        if (profile == null || profile.id() == null) {
            return Optional.empty();
        }
        if (Objects.equals(profile.id(), 1L) && Objects.equals(normalize(profile.username()), adminUsername)) {
            return Optional.of(new AdminProfile(1L, adminUsername, adminNickname, ALL_ADMIN_PERMISSIONS));
        }
        AdminStaffItem staff = adminStaff.get(profile.id());
        if (staff == null || !"ENABLED".equalsIgnoreCase(defaultText(staff.status(), ""))) {
            return Optional.empty();
        }
        return Optional.of(new AdminProfile(staff.id(), staff.account(), staff.nickname(), staff.permissions()));
    }

    public void logoutAdmin(String token) {
        String cleanToken = cleanBearerToken(token);
        if (!StringUtils.hasText(cleanToken)) {
            return;
        }
        adminTokens.remove(cleanToken);
        adminTokenExpiresAt.remove(cleanToken);
        if (securityStateStore != null) {
            securityStateStore.deleteAdminToken(cleanToken);
        }
    }

    private void logoutAllAdmins() {
        adminTokens.clear();
        adminTokenExpiresAt.clear();
        if (securityStateStore != null) {
            securityStateStore.deleteAdminSessions();
        }
    }

    public synchronized AdminProfile updateSuperAdminCredentials(String token, AdminCredentialRequest request) {
        String cleanToken = cleanBearerToken(token);
        AdminProfile operator = findAdminByToken(cleanToken)
            .orElseThrow(() -> new IllegalStateException("登录已失效，请重新登录"));
        if (!Objects.equals(operator.id(), 1L)) {
            throw new IllegalStateException("只有超级管理员可以修改超级管理员账号密码");
        }

        String currentPassword = defaultText(request == null ? "" : request.currentPassword(), "");
        if (!ADMIN_PASSWORD_ENCODER.matches(currentPassword, adminPasswordBcrypt)) {
            throw new IllegalArgumentException("当前密码不正确");
        }

        String nextAccount = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(nextAccount)) {
            throw new IllegalArgumentException("请填写超级管理员登录账号");
        }
        validateSuperAdminAccount(nextAccount);

        String nextNickname = defaultText(request == null ? "" : request.nickname(), "").trim();
        if (!StringUtils.hasText(nextNickname)) {
            nextNickname = "运营管理员";
        }

        String nextPasswordHash = adminPasswordBcrypt;
        String nextPassword = defaultText(request == null ? "" : request.newPassword(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (StringUtils.hasText(nextPassword) || StringUtils.hasText(confirmPassword)) {
            validateNewPassword(nextPassword, confirmPassword);
            nextPasswordHash = ADMIN_PASSWORD_ENCODER.encode(nextPassword);
        }

        adminUsername = nextAccount;
        adminNickname = nextNickname;
        adminPasswordBcrypt = nextPasswordHash;
        persistRuntimeSetting(SUPER_ADMIN_USERNAME_KEY, adminUsername);
        persistRuntimeSetting(SUPER_ADMIN_NICKNAME_KEY, adminNickname);
        persistRuntimeSetting(SUPER_ADMIN_PASSWORD_KEY, adminPasswordBcrypt);
        logoutAllAdmins();
        appendOperation("SUPER_ADMIN_CREDENTIAL_UPDATE", "ADMIN", "1", adminUsername);
        return new AdminProfile(1L, adminUsername, adminNickname, ALL_ADMIN_PERMISSIONS);
    }

    public synchronized List<AdminStaffItem> listAdminStaff() {
        return adminStaff.values().stream()
            .sorted(Comparator.comparing(AdminStaffItem::id))
            .toList();
    }

    public synchronized AdminStaffItem createAdminStaff(AdminStaffRequest request) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请填写员工登录账号");
        }
        validateAdminStaffAccount(account, null);
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        validateNewPassword(password, confirmPassword);
        Long id = allocateNextCandidateId(adminStaffId, maxAdminStaffId());
        OffsetDateTime now = OffsetDateTime.now();
        AdminStaffItem item = new AdminStaffItem(
            id,
            account,
            defaultText(request == null ? "" : request.nickname(), account).trim(),
            normalizeAdminStaffStatus(request == null ? "" : request.status()),
            normalizeAdminPermissions(request == null ? List.of() : request.permissions()),
            now,
            now
        );
        adminStaff.put(id, item);
        adminStaffPasswordHashes.put(id, ADMIN_PASSWORD_ENCODER.encode(password));
        persistAdminStaff();
        appendOperation("ADMIN_STAFF_CREATE", "ADMIN_STAFF", String.valueOf(id), account);
        return item;
    }

    public synchronized AdminStaffItem updateAdminStaff(Long id, AdminStaffRequest request) {
        AdminStaffItem current = adminStaff.get(id);
        if (current == null) {
            throw new IllegalArgumentException("员工账号不存在");
        }
        String account = normalize(defaultText(request == null ? "" : request.account(), current.account()));
        validateAdminStaffAccount(account, id);
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (StringUtils.hasText(password) || StringUtils.hasText(confirmPassword)) {
            validateNewPassword(password, confirmPassword);
            adminStaffPasswordHashes.put(id, ADMIN_PASSWORD_ENCODER.encode(password));
        }
        AdminStaffItem next = new AdminStaffItem(
            current.id(),
            account,
            defaultText(request == null ? "" : request.nickname(), current.nickname()).trim(),
            normalizeAdminStaffStatus(request == null ? "" : request.status()),
            normalizeAdminPermissions(request == null ? current.permissions() : request.permissions()),
            current.createdAt(),
            OffsetDateTime.now()
        );
        adminStaff.put(id, next);
        persistAdminStaff();
        appendOperation("ADMIN_STAFF_UPDATE", "ADMIN_STAFF", String.valueOf(id), account);
        return next;
    }

    public synchronized void deleteAdminStaff(Long id) {
        AdminStaffItem removed = adminStaff.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("员工账号不存在");
        }
        adminStaffPasswordHashes.remove(id);
        persistAdminStaff();
        appendOperation("ADMIN_STAFF_DELETE", "ADMIN_STAFF", String.valueOf(id), removed.account());
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
                    group.priceLimitEnabled(),
                    priceLimitNotice(group.priceLimitNotice()),
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
                group.priceLimitEnabled(),
                priceLimitNotice(group.priceLimitNotice()),
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
            request.priceLimitEnabled() == null || Boolean.TRUE.equals(request.priceLimitEnabled()),
            priceLimitNotice(request.priceLimitNotice()),
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
            request == null || request.priceLimitEnabled() == null ? current.priceLimitEnabled() : request.priceLimitEnabled(),
            request == null || request.priceLimitNotice() == null ? current.priceLimitNotice() : priceLimitNotice(request.priceLimitNotice()),
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

    public List<PaymentChannelItem> listPaymentChannels() {
        ensurePaymentChannelsReady();
        return paymentChannels.values().stream()
            .sorted(Comparator.comparing(PaymentChannelItem::sort).thenComparing(PaymentChannelItem::id))
            .toList();
    }

    public List<PaymentChannelItem> listEnabledPaymentChannels(String terminal) {
        String normalizedTerminal = normalizeTerminal(terminal);
        return listPaymentChannels().stream()
            .filter(item -> "ENABLED".equalsIgnoreCase(defaultText(item.status(), "ENABLED")))
            .filter(item -> paymentChannelAllowsTerminal(item, normalizedTerminal))
            .map(this::publicPaymentChannel)
            .toList();
    }

    public synchronized PaymentChannelItem createPaymentChannel(PaymentChannelRequest request) {
        ensurePaymentChannelsReady();
        String code = normalizePaymentChannelCode(request == null ? "" : request.code());
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("支付通道编码不能为空");
        }
        if (paymentChannelCodeExists(code, null)) {
            throw new IllegalStateException("支付通道编码已存在");
        }
        Long id = allocateIncrementingId(paymentChannelId, maxPaymentChannelId());
        OffsetDateTime now = OffsetDateTime.now();
        PaymentChannelItem item = new PaymentChannelItem(
            id,
            code,
            requiredText(request == null ? "" : request.name(), "支付通道"),
            normalizePaymentChannelType(request == null ? "" : request.type()),
            normalizePaymentTerminals(request == null ? null : request.terminals()),
            normalizePaymentChannelStatus(request == null ? "" : request.status()),
            request == null || request.sort() == null ? (int) (id * 10) : request.sort(),
            normalizePaymentChannelConfig(request == null ? null : request.config()),
            defaultText(request == null ? "" : request.remark(), ""),
            now,
            now
        );
        paymentChannels.put(id, item);
        persistPaymentChannels();
        return item;
    }

    public synchronized PaymentChannelItem updatePaymentChannel(Long id, PaymentChannelRequest request) {
        ensurePaymentChannelsReady();
        PaymentChannelItem current = paymentChannels.get(id);
        if (current == null) {
            throw new IllegalArgumentException("payment channel not found");
        }
        String code = normalizePaymentChannelCode(firstText(request == null ? "" : request.code(), current.code(), current.code()));
        if (paymentChannelCodeExists(code, id)) {
            throw new IllegalStateException("支付通道编码已存在");
        }
        PaymentChannelItem next = new PaymentChannelItem(
            current.id(),
            code,
            requiredText(request == null ? "" : request.name(), current.name()),
            normalizePaymentChannelType(defaultText(request == null ? "" : request.type(), current.type())),
            normalizePaymentTerminals(request == null || request.terminals() == null ? current.terminals() : request.terminals()),
            normalizePaymentChannelStatus(defaultText(request == null ? "" : request.status(), current.status())),
            request == null || request.sort() == null ? current.sort() : request.sort(),
            normalizePaymentChannelConfig(request == null || request.config() == null ? current.config() : request.config()),
            defaultText(request == null ? "" : request.remark(), current.remark()),
            current.createdAt(),
            OffsetDateTime.now()
        );
        paymentChannels.put(id, next);
        persistPaymentChannels();
        return next;
    }

    public synchronized PaymentChannelItem updatePaymentChannelStatus(Long id, boolean enabled) {
        PaymentChannelItem current = paymentChannels.get(id);
        if (current == null) {
            throw new IllegalArgumentException("payment channel not found");
        }
        PaymentChannelItem next = new PaymentChannelItem(
            current.id(),
            current.code(),
            current.name(),
            current.type(),
            current.terminals(),
            enabled ? "ENABLED" : "DISABLED",
            current.sort(),
            current.config(),
            current.remark(),
            current.createdAt(),
            OffsetDateTime.now()
        );
        paymentChannels.put(id, next);
        persistPaymentChannels();
        return next;
    }

    public synchronized void deletePaymentChannel(Long id) {
        ensurePaymentChannelsReady();
        PaymentChannelItem current = paymentChannels.get(id);
        if (current == null) {
            throw new IllegalArgumentException("payment channel not found");
        }
        paymentChannels.remove(id);
        persistPaymentChannels();
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

    public synchronized UserItem updateUserCredentials(Long userId, AdminUserCredentialRequest request) {
        UserItem user = requiredUser(userId);
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请输入用户账号");
        }
        String mobile = "";
        String email = "";
        if (account.contains("@")) {
            if (!account.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new IllegalArgumentException("请输入正确的邮箱账号");
            }
            email = account;
        } else {
            if (!account.matches("^1[3-9]\\d{9}$")) {
                throw new IllegalArgumentException("请输入正确的手机号账号");
            }
            mobile = account;
        }
        boolean accountExists = allUserSnapshots().stream()
            .filter(item -> !Objects.equals(item.id(), userId))
            .anyMatch(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account));
        if (accountExists) {
            throw new IllegalArgumentException("该账号已被其他用户使用");
        }

        String nickname = defaultText(request == null ? "" : request.nickname(), "").trim();
        if (!StringUtils.hasText(nickname)) {
            nickname = StringUtils.hasText(user.nickname()) ? user.nickname() : account;
        }
        String newPassword = defaultText(request == null ? "" : request.newPassword(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        boolean passwordChanged = StringUtils.hasText(newPassword) || StringUtils.hasText(confirmPassword);
        if (passwordChanged) {
            validateNewPassword(newPassword, confirmPassword);
            String nextHash = ADMIN_PASSWORD_ENCODER.encode(newPassword);
            userPasswordHashes.put(userId, nextHash);
            persistRuntimeSetting("user.password." + userId, nextHash);
            invalidateUserTokens(userId);
        }

        UserItem next = new UserItem(
            user.id(),
            user.avatar(),
            mobile,
            email,
            nickname,
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
        users.put(userId, next);
        persistUserSnapshot(next);
        appendOperation("USER_CREDENTIAL_UPDATE", "USER", String.valueOf(userId), passwordChanged ? "管理员修改账号并重置密码" : "管理员修改账号");
        return withGroupName(next);
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

    public synchronized void deleteGoods(Long targetGoodsId) {
        GoodsItem item = findGoodsSnapshot(targetGoodsId).orElse(null);
        if (item == null) {
            throw new IllegalArgumentException("goods not found");
        }
        goods.remove(targetGoodsId);
        cards.entrySet().removeIf(entry -> Objects.equals(entry.getValue().goodsId(), targetGoodsId));
        List<Long> channelIds = allGoodsChannelSnapshots().stream()
            .filter(channel -> Objects.equals(channel.goodsId(), targetGoodsId))
            .map(GoodsChannelItem::id)
            .filter(Objects::nonNull)
            .toList();
        channelIds.forEach(channelId -> {
            goodsChannels.remove(channelId);
            productMonitorStates.remove(channelId);
            productMonitorLogs.entrySet().removeIf(entry -> Objects.equals(entry.getValue().channelId(), channelId));
        });
        deletePersistentGoods(targetGoodsId);
        deletePersistentGoodsChannelsByGoods(targetGoodsId);
        deletePersistentCardsByGoods(targetGoodsId);
        appendOperation("GOODS_DELETE", "GOODS", String.valueOf(targetGoodsId), item.goodsName());
    }

    public ProductMonitorOverview productMonitorOverview() {
        return new ProductMonitorOverview(listProductMonitorItems(), listProductMonitorLogs());
    }

    public List<ProductMonitorItem> listProductMonitorItems() {
        OffsetDateTime now = OffsetDateTime.now();
        return allGoodsChannelSnapshots().stream()
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
        return allGoodsChannelSnapshots().stream()
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
        return allGoodsChannelSnapshots().stream()
            .filter(this::isProductMonitorChannel)
            .sorted(Comparator.comparing(GoodsChannelItem::id))
            .map(channel -> scanProductMonitorChannel(channel.id(), manual))
            .filter(Objects::nonNull)
            .toList();
    }

    public synchronized ProductMonitorScanResult scanProductMonitorChannel(Long channelId, boolean manual) {
        GoodsChannelItem channel = findGoodsChannelSnapshot(channelId).orElse(null);
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (channel == null || !isProductMonitorChannel(channel)) {
            return null;
        }

        productMonitorStates.put(channelId, ensureProductMonitorState(channelId, startedAt).start(startedAt));

        GoodsItem current = findGoodsSnapshot(channel.goodsId()).orElse(null);
        SupplierItem supplier = null;
        List<String> changes = new ArrayList<>();
        String result = "NO_CHANGE";
        String message = "本轮扫描无变动";
        boolean changed = false;

        try {
            if (current == null) {
                throw new IllegalStateException("本地商品不存在");
            }
            supplier = requiredSupplier(channel.supplierId());
            if (!"ENABLED".equals(supplier.status())) {
                throw new IllegalStateException("供应商已停用");
            }
            if (!"ENABLED".equals(channel.status())) {
                throw new IllegalStateException("渠道已停用");
            }

            boolean primaryChannel = isPrimaryProductMonitorChannel(channel);
            MonitoredRemoteGoods remote = monitoredRemoteGoods(current, channel, supplier);
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
        String appKey = defaultText(request.appKey(), defaultText(request.appId(), ""));
        String appSecret = defaultText(request.appSecret(), "");
        String apiKey = defaultText(request.apiKey(), appSecret);
        String apiKeyMasked = StringUtils.hasText(request.apiKeyMasked()) ? request.apiKeyMasked().trim() : mask(apiKey);
        String platformType = defaultText(request.platformType(), "CUSTOM");
        String appId = firstText(request.appId(), request.userId(), appKey);
        String userId = firstText(request.userId(), appId, appKey);
        SupplierItem item = new SupplierItem(
            id,
            name,
            platformType,
            defaultText(request.baseUrl(), ""),
            appKey,
            mask(appSecret),
            userId,
            appId,
            apiKey,
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
        String userId = isApiSupplierPlatform(platformType) || isFuluPlatform(platformType) || isFengzhushouPlatform(platformType) || isChengquanPlatform(platformType) || isFanchenPlatform(platformType) || isJingzhaoPlatform(platformType)
            ? firstText(request.userId(), appId, defaultText(current.userId(), appKey))
            : defaultText(request.userId(), current.userId());
        String appSecretMasked = current.appSecretMasked();
        if (StringUtils.hasText(request.appSecret())) {
            appSecretMasked = mask(request.appSecret());
        }
        String apiKey = current.apiKey();
        String apiKeyMasked = current.apiKeyMasked();
        if (StringUtils.hasText(request.apiKey())) {
            apiKey = request.apiKey().trim();
            supplierApiKeys.put(id, apiKey);
            apiKeyMasked = mask(apiKey);
        } else if (StringUtils.hasText(request.appSecret())) {
            apiKey = request.appSecret().trim();
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
            apiKey,
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
        SupplierItem next;
        if (isKasushouSupplier(item)) {
            next = refreshKasushouBalance(item);
        } else if (isKakayunSupplier(item)) {
            next = refreshKakayunBalance(item);
        } else if (isFuluSupplier(item)) {
            next = refreshFuluBalance(item);
        } else if (isFengzhushouSupplier(item)) {
            next = refreshFengzhushouBalance(item);
        } else if (isChengquanSupplier(item)) {
            next = refreshChengquanBalance(item);
        } else if (isFanchenSupplier(item)) {
            next = refreshFanchenBalance(item);
        } else if (isJingzhaoSupplier(item)) {
            next = refreshJingzhaoBalance(item);
        } else {
            throw new IllegalArgumentException("当前供应商不支持远程刷新余额，请手动维护余额");
        }
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized SupplierItem testSupplierConnection(Long id) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        SupplierItem next = isKasushouSupplier(item)
            ? testKasushouConnection(item)
            : (isKakayunSupplier(item)
                ? testKakayunConnection(item)
                : (isFuluSupplier(item)
                    ? testFuluConnection(item)
                    : (isFengzhushouSupplier(item)
                        ? testFengzhushouConnection(item)
                        : (isChengquanSupplier(item)
                            ? testChengquanConnection(item)
                            : (isFanchenSupplier(item)
                                ? testFanchenConnection(item)
                                : (isJingzhaoSupplier(item) ? testJingzhaoConnection(item) : item.withBalance(item.balance())))))));
        suppliers.put(id, next);
        persistSupplier(next);
        return next;
    }

    public synchronized RemoteGoodsSyncResult syncRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        if (isFuluSupplier(item) || isFengzhushouSupplier(item)) {
            throw new IllegalArgumentException(platformLabelForManualSupplier(item) + "不提供上游商品列表，请在商品对接里手动填写上游商品编码");
        }
        if (!supportsRemoteGoodsSync(item)) {
            throw new IllegalArgumentException("supplier platformType must support remote goods sync");
        }

        int page = request == null || request.page() == null ? 1 : Math.max(1, request.page());
        int limit = request == null || request.limit() == null ? 20 : Math.max(1, Math.min(request.limit(), 100));
        Long cateId = request == null ? null : request.cateId();
        String keyword = request == null ? "" : defaultText(request.keyword(), "").trim();

        RemoteGoodsSyncResult result = fetchIntegratedRemoteGoods(item, cateId, keyword, page, limit);
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

    public synchronized GoodsIntegrationItem remoteGoodsSnapshot(Long supplierId, String supplierGoodsId) {
        SupplierItem supplier = requiredSupplier(supplierId);
        if (!"ENABLED".equals(supplier.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        String normalizedId = defaultText(supplierGoodsId, "").trim();
        if (!StringUtils.hasText(normalizedId)) {
            throw new IllegalArgumentException("supplierGoodsId is required");
        }
        RemoteGoodsItem remote = fetchRemoteGoodsSnapshot(supplier, normalizedId, false);
        return remoteGoodsIntegration(supplier, remote);
    }

    public synchronized RemoteGoodsSyncResult sourceConnectRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }

        if (isFuluSupplier(item)) {
            throw new IllegalArgumentException("福禄新平台不支持获取上游商品，请手动填写 product_id 创建商品对接");
        }
        if (!supportsRemoteGoodsSync(item)) {
            throw new IllegalArgumentException("当前供应商不支持远程拉取商品，请手动创建或绑定上游商品编码");
        }
        RemoteGoodsSyncResult result = syncRemoteGoods(id, request);
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

            try {
                RemoteGoodsItem remote = fetchRemoteGoodsSnapshot(supplier, normalizedId, false);
                GoodsIntegrationItem remoteIntegration = remoteGoodsIntegration(supplier, remote);
                Optional<GoodsChannelItem> existing = allGoodsChannelSnapshots().stream()
                    .filter(channel -> Objects.equals(channel.supplierId(), supplierId))
                    .filter(channel -> Objects.equals(channel.supplierGoodsId(), normalizedId))
                    .filter(channel -> findGoodsSnapshot(channel.goodsId()).isPresent())
                    .findFirst();
                List<String> accountTypes = validateEnabledRechargeFieldCodes(cloneItem.accountTypes());
                String goodsName = firstText(remote.goodsName(), cloneItem.name(), normalizedId);
                BigDecimal price = defaultDecimal(remote.goodsPrice());
                BigDecimal originalPrice = remote.faceValue() == null ? price : remote.faceValue();
                int stock = Math.max(0, remote.stockNum() == null ? 0 : remote.stockNum());
                String status = remoteGoodsStatus(remote);
                List<String> benefitDurations = normalizedBenefitDurations(cloneItem.benefitDurations(), goodsName);
                int priority = cloneItem.priority() == null ? normalizedPriority(request.priority()) : normalizedPriority(cloneItem.priority());
                int timeoutSeconds = cloneItem.timeoutSeconds() == null
                    ? normalizedChannelTimeout(request.timeoutSeconds())
                    : normalizedChannelTimeout(cloneItem.timeoutSeconds());
                CreateGoodsRequest goodsRequest = new CreateGoodsRequest(
                    cloneItem.categoryId() == null ? request.categoryId() : cloneItem.categoryId(),
                    goodsName,
                    goodsName,
                    "由 " + supplier.name() + " 一键对接创建",
                    defaultText(cloneItem.description(), "货源对接自动创建，已绑定上游商品 " + normalizedId),
                    benefitDurations,
                    "",
                    "",
                    titleContainsPriceLimited(goodsName),
                    inferredPriceLimitText(goodsName),
                    defaultText(cloneItem.coverUrl(), ""),
                    List.of(),
                    List.of(),
                    List.of(remoteIntegration),
                    true,
                    true,
                    GoodsType.DIRECT,
                    "GENERAL",
                    price,
                    originalPrice,
                    1,
                    Boolean.TRUE.equals(cloneItem.requireRechargeAccount()),
                    accountTypes,
                    defaultText(cloneItem.priceTemplateId(), "follow-upstream"),
                    defaultText(cloneItem.priceMode(), "FIXED"),
                    cloneItem.priceCoefficient() == null ? BigDecimal.ONE : cloneItem.priceCoefficient(),
                    cloneItem.priceFixedAdd() == null ? BigDecimal.ZERO : cloneItem.priceFixedAdd(),
                    stock,
                    status,
                    List.of(),
                    normalizePlatforms(cloneItem.availablePlatforms()),
                    normalizePlatforms(cloneItem.forbiddenPlatforms()),
                    null
                );
                if (existing.isPresent()) {
                    skippedCount++;
                    GoodsChannelItem channel = existing.get();
                    GoodsItem updated = updateGoods(channel.goodsId(), goodsRequest);
                    GoodsChannelItem updatedChannel = new GoodsChannelItem(
                        channel.id(),
                        channel.goodsId(),
                        channel.supplierId(),
                        supplier.name(),
                        channel.supplierGoodsId(),
                        priority,
                        timeoutSeconds,
                        "ENABLED",
                        channel.createdAt()
                    );
                    goodsChannels.put(updatedChannel.id(), updatedChannel);
                    persistGoodsChannel(updatedChannel);
                    scanProductMonitorChannel(updatedChannel.id(), true);
                    results.add(new SourceCloneItem(normalizedId, updated.goodsName(), "SKIPPED", updated.id(), updatedChannel.id(), "已存在对接关系，已更新本地商品设置"));
                    continue;
                }
                GoodsItem created = createGoods(goodsRequest);
                GoodsChannelItem channel = createGoodsChannel(created.id(), new CreateGoodsChannelRequest(
                    supplierId,
                    normalizedId,
                    priority,
                    timeoutSeconds,
                    "ENABLED"
                ));
                scanProductMonitorChannel(channel.id(), true);
                createdCount++;
                results.add(new SourceCloneItem(normalizedId, goodsName, "CREATED", created.id(), channel.id(), "已创建本地商品、绑定货源，并开启轮询监控"));
            } catch (RuntimeException ex) {
                failedCount++;
                results.add(new SourceCloneItem(normalizedId, normalizedId, "FAILED", null, null, ex.getMessage()));
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
                null,
                List.of(),
                false,
                null,
                null,
                null,
                null,
                List.of("private"),
                List.of(),
                request.priority(),
                request.timeoutSeconds()
            ))
            .toList();
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
        return remoteGoodsSaleStatus(remote);
    }

    private String remoteGoodsSaleStatus(RemoteGoodsItem remote) {
        if (remote == null) {
            return "UNKNOWN";
        }
        String status = normalize(remote.status());
        if (isRemoteOnSaleStatus(status)) {
            return "ON_SALE";
        }
        if (isRemoteOffSaleStatus(status)) {
            return "OFF_SALE";
        }
        if (remote.canNoBuy() == Boolean.TRUE) {
            return "OFF_SALE";
        }
        if (remote.canBuy() == Boolean.TRUE) {
            return "ON_SALE";
        }
        if (remote.canBuy() == Boolean.FALSE) {
            return "OFF_SALE";
        }
        return "ON_SALE";
    }

    private boolean isRemoteOnSaleStatus(String status) {
        return List.of("1", "on", "true", "yes", "y", "enabled", "enable", "normal", "online", "on_sale", "onsale", "sale", "selling")
            .contains(status)
            || List.of("正常", "上架", "在售", "可售", "可购买", "销售中").contains(status);
    }

    private boolean isRemoteOffSaleStatus(String status) {
        return List.of("0", "2", "false", "no", "n", "disabled", "disable", "offline", "off_sale", "offsale", "sold_out", "closed", "stop")
            .contains(status)
            || List.of("下架", "停售", "不可售", "不可购买", "售罄", "关闭").contains(status);
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
            .map(this::withLatestSupplierNames)
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
            .map(this::withLatestSupplierNames)
            .toList();
    }

    public Optional<OrderItem> findOrder(String orderNo) {
        Optional<OrderItem> persistent = persistentOrder(orderNo);
        if (persistent.isPresent()) {
            return Optional.of(withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(persistent.get(), false)));
        }
        OrderItem order = orders.get(orderNo);
        OrderItem active = order == null ? null : expireOrderIfNeeded(order, OffsetDateTime.now());
        return Optional.ofNullable(active == null ? null : withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(active, false)));
    }

    public synchronized OrderItem refreshOrderCallbackInfo(String orderNo) {
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        OrderItem active = expireOrderIfNeeded(order, OffsetDateTime.now());
        if (active == null) {
            throw new IllegalArgumentException("order not found");
        }
        return withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(active, true));
    }

    public synchronized OrderRefreshResult refreshUnfinishedOrderStatuses() {
        List<OrderItem> candidates = allOrderSnapshots().stream()
            .filter(this::canRefreshUpstreamOrder)
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
        int refreshed = 0;
        int changed = 0;
        int failed = 0;
        String firstError = "";
        for (OrderItem order : candidates) {
            try {
                OrderItem next = refreshUpstreamOrderStatusIfNeeded(order, true);
                refreshed++;
                if (next.status() != order.status()
                    || !Objects.equals(next.deliveryMessage(), order.deliveryMessage())
                    || !Objects.equals(next.channelAttempts(), order.channelAttempts())) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                failed++;
                if (!StringUtils.hasText(firstError)) {
                    firstError = ex.getMessage();
                }
            }
        }
        return new OrderRefreshResult(candidates.size(), refreshed, changed, failed, defaultText(firstError, ""));
    }

    public synchronized String handleFuluOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fulu callback body is empty");
        }
        SupplierItem supplier = resolveFuluCallbackSupplier(supplierId, body);
        verifyFuluCallbackSign(body, supplier);
        String bizContent = callbackText(body.get("biz_content"));
        if (!StringUtils.hasText(bizContent)) {
            throw new IllegalArgumentException("fulu callback biz_content is required");
        }
        FuluOrderStatus upstream;
        try {
            upstream = fuluOrderStatusFromResult(bizContent);
        } catch (RuntimeException ex) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(bizContent);
                upstream = new FuluOrderStatus(
                    textValue(node, "order_id", "orderId"),
                    textValue(node, "customer_order_no", "customerOrderNo"),
                    textValue(node, "product_id", "productId"),
                    textValue(node, "product_name", "productName"),
                    intValue(firstExisting(node, "order_status", "orderStatus", "status"), 0),
                    textValue(node, "charge_remark", "chargeRemark", "message", "msg"),
                    optionalDecimalValue(node, "total_price", "totalPrice", "customer_price", "customerPrice"),
                    List.of(),
                    abbreviate(node.toString(), 1200)
                );
            } catch (JsonProcessingException jsonEx) {
                throw ex;
            }
        }
        String orderNo = upstream.externalOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fulu callback customer_order_no is required");
        }
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = order.channelAttempts() == null ? null : order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null) {
            throw new IllegalArgumentException("fulu callback order channel mismatch");
        }
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
        ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(order.channelAttempts());
        int index = nextAttempts.lastIndexOf(successAttempt);
        if (index >= 0) {
            nextAttempts.set(index, enrichedAttempt);
        }
        OrderStatus nextStatus = fuluLocalOrderStatus(upstream.status(), order.status());
        OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
        OrderItem next = order.withProcurementResult(
            nextStatus,
            mergedDeliveryItems(order.deliveryItems(), upstream),
            List.copyOf(nextAttempts),
            fuluDeliveryMessage(upstream),
            order.paidAt(),
            deliveredAt
        );
        orders.put(next.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return "success";
    }

    public synchronized Map<String, String> handleFengzhushouOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fengzhushou callback body is empty");
        }
        SupplierItem supplier = resolveFengzhushouCallbackSupplier(supplierId, body);
        verifyFengzhushouCallbackSign(body, supplier);
        String orderNo = callbackText(body.get("channelOrderNo"));
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fengzhushou callback channelOrderNo is required");
        }
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = order.channelAttempts() == null ? null : order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null) {
            throw new IllegalArgumentException("fengzhushou callback order channel mismatch");
        }
        FengzhushouOrderStatus upstream = new FengzhushouOrderStatus(
            callbackText(body.get("orderNo")),
            orderNo,
            intValue(body.get("retcode"), 0),
            callbackText(body.get("msg")),
            null,
            abbreviate(callbackText(body), 1200)
        );
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
        ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(order.channelAttempts());
        int index = nextAttempts.lastIndexOf(successAttempt);
        if (index >= 0) {
            nextAttempts.set(index, enrichedAttempt);
        }
        OrderStatus nextStatus = fengzhushouLocalOrderStatus(upstream.status(), order.status());
        OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
        OrderItem next = order.withProcurementResult(
            nextStatus,
            mergedDeliveryItems(order.deliveryItems(), upstream),
            List.copyOf(nextAttempts),
            fengzhushouDeliveryMessage(upstream),
            order.paidAt(),
            deliveredAt
        );
        orders.put(next.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return Map.of("code", "0");
    }

    public synchronized String handleChengquanOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("chengquan callback body is empty");
        }
        SupplierItem supplier = resolveChengquanCallbackSupplier(supplierId, body);
        verifyChengquanCallbackSign(body, supplier);
        String orderNo = callbackText(body.get("order_no"));
        if (!StringUtils.hasText(orderNo)) {
            orderNo = callbackText(body.get("orderNo"));
        }
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("chengquan callback order_no is required");
        }
        String normalizedOrderNo = orderNo;
        OrderItem order = persistentOrder(normalizedOrderNo).orElseGet(() -> orders.get(normalizedOrderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = order.channelAttempts() == null ? null : order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null) {
            throw new IllegalArgumentException("chengquan callback order channel mismatch");
        }
        ChengquanOrderStatus upstream = new ChengquanOrderStatus(
            firstText(callbackText(body.get("cq_order_no")), callbackText(body.get("platform_order_no")), ""),
            orderNo,
            firstText(callbackText(body.get("status")), callbackText(body.get("order_status")), ""),
            firstText(callbackText(body.get("message")), callbackText(body.get("msg")), ""),
            decimalValue(callbackText(body.get("amount"))),
            abbreviate(callbackText(body), 1200)
        );
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
        ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(order.channelAttempts());
        int index = nextAttempts.lastIndexOf(successAttempt);
        if (index >= 0) {
            nextAttempts.set(index, enrichedAttempt);
        }
        OrderStatus nextStatus = chengquanLocalOrderStatus(upstream.status(), order.status());
        OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
        OrderItem next = order.withProcurementResult(
            nextStatus,
            mergedDeliveryItems(order.deliveryItems(), upstream),
            List.copyOf(nextAttempts),
            chengquanDeliveryMessage(upstream),
            order.paidAt(),
            deliveredAt
        );
        orders.put(next.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return "OK";
    }

    public synchronized String handleFanchenOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fanchen callback body is empty");
        }
        SupplierItem supplier = resolveFanchenCallbackSupplier(supplierId, body);
        verifyFanchenCallbackSign(body, supplier);
        String orderNo = callbackText(body.get("sporderid"));
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fanchen callback sporderid is required");
        }
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = order.channelAttempts() == null ? null : order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null) {
            throw new IllegalArgumentException("fanchen callback order channel mismatch");
        }
        FanchenOrderStatus upstream = new FanchenOrderStatus(
            callbackText(body.get("orderid")),
            orderNo,
            callbackText(body.get("resultno")),
            callbackText(body.get("remark1")),
            decimalValue(callbackText(body.get("parvalue"))),
            List.of(),
            abbreviate(callbackText(body), 1200)
        );
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
        ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(order.channelAttempts());
        int index = nextAttempts.lastIndexOf(successAttempt);
        if (index >= 0) {
            nextAttempts.set(index, enrichedAttempt);
        }
        OrderStatus nextStatus = fanchenLocalOrderStatus(upstream.status(), order.status());
        OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
        OrderItem next = order.withProcurementResult(
            nextStatus,
            mergedDeliveryItems(order.deliveryItems(), upstream),
            List.copyOf(nextAttempts),
            fanchenDeliveryMessage(upstream),
            order.paidAt(),
            deliveredAt
        );
        orders.put(next.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return "OK";
    }

    public synchronized String handleJingzhaoOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("jingzhao callback body is empty");
        }
        SupplierItem supplier = resolveJingzhaoCallbackSupplier(supplierId, body);
        verifyJingzhaoCallbackSign(body, supplier);
        JsonNode callbackNode = OBJECT_MAPPER.convertValue(body, JsonNode.class);
        JsonNode statusNode = callbackNode.path("data").isObject() ? callbackNode.path("data") : callbackNode;
        String orderNo = firstText(
            callbackText(body.get("outer_order_id")),
            callbackText(body.get("outerOrderId")),
            textValue(statusNode, "outer_order_id", "outerOrderId")
        );
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("jingzhao callback outer_order_id is required");
        }
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = order.channelAttempts() == null ? null : order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null) {
            throw new IllegalArgumentException("jingzhao callback order channel mismatch");
        }
        JingzhaoOrderStatus upstream = jingzhaoOrderStatusFromNode(statusNode, orderNo);
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
        ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(order.channelAttempts());
        int index = nextAttempts.lastIndexOf(successAttempt);
        if (index >= 0) {
            nextAttempts.set(index, enrichedAttempt);
        }
        OrderStatus nextStatus = jingzhaoLocalOrderStatus(upstream.status(), order.status());
        OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
        OrderItem next = order.withProcurementResult(
            nextStatus,
            mergedDeliveryItems(order.deliveryItems(), upstream),
            List.copyOf(nextAttempts),
            jingzhaoDeliveryMessage(upstream),
            order.paidAt(),
            deliveredAt
        );
        orders.put(next.orderNo(), next);
        persistOrderSnapshot(next);
        publishOrder(next);
        return "ok";
    }

    private boolean canRefreshUpstreamOrder(OrderItem order) {
        return order != null
            && order.goodsType() == GoodsType.DIRECT
            && order.status() == OrderStatus.PROCURING
            && order.channelAttempts() != null
            && order.channelAttempts().stream()
                .anyMatch(attempt -> attempt.supplierId() != null
                    && ("SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status())));
    }

    public Optional<OrderItem> findOrderForUser(String orderNo, Long userId) {
        return findOrder(orderNo).filter(order -> Objects.equals(order.userId(), userId));
    }

    public Optional<PaymentItem> findPaymentForUser(String paymentNo, Long userId) {
        PaymentItem payment = findPaymentSnapshot(paymentNo).orElse(null);
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
        Optional<MemberApiCredentialItem> persistent = persistentMemberCredential(userId);
        if (persistent.isPresent()) {
            MemberApiCredentialItem item = persistent.get();
            memberCredentials.put(item.appKey(), item);
            return item;
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
        persistMemberCredential(next);
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
        if (securityStateStore != null) {
            Optional<Boolean> redisReplay = securityStateStore.markMemberApiNonceReplay(nonceKey, MEMBER_API_NONCE_TTL);
            if (redisReplay.isPresent()) {
                return redisReplay.get();
            }
        }
        cleanupExpiredMemberNonces(now);
        OffsetDateTime existing = memberNonceExpiresAt.putIfAbsent(nonceKey, now.plus(MEMBER_API_NONCE_TTL));
        return existing != null && existing.isAfter(now);
    }

    private void cleanupExpiredMemberNonces(OffsetDateTime now) {
        memberNonceExpiresAt.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    public synchronized OrderItem createMemberOrder(CreateOrderRequest request, Long userId) {
        return createMemberOrder(request, userId, "");
    }

    public synchronized OrderItem createMemberOrder(CreateOrderRequest request, Long userId, String orderIp) {
        OrderItem order = createOrder(request, userId, orderIp, "api");
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
        PaymentItem payment = findPaymentSnapshot(paymentNo).orElse(null);
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
        } else if (supplier.balance() != null && supplier.balance().compareTo(order.payAmount()) < 0) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商余额不足"));
        } else {
            try {
                ProcurementSubmitResult result = submitProcurementOrder(order, channel, supplier);
                attempts.add(attempt(channel, "SUCCESS", result.attemptMessage()));
                OrderItem procuring = order.withProcurementResult(
                    OrderStatus.PROCURING,
                    result.deliveryItems(),
                    List.copyOf(attempts),
                    "指定渠道重试成功：已提交到 " + channel.supplierName() + "，等待上游处理",
                    order.paidAt() == null ? OffsetDateTime.now() : order.paidAt(),
                    null
                );
                orders.put(orderNo, procuring);
                persistOrderSnapshot(procuring);
                appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel submitted to upstream");
                publishOrder(procuring);
                return procuring;
            } catch (RuntimeException ex) {
                attempts.add(attempt(channel, "FAILED", "指定渠道提交失败：" + ex.getMessage()));
            }
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
        boolean persistentDeleted = deletePersistentOrderData(orderNo);
        if (removed == null && !persistentDeleted) {
            throw new IllegalArgumentException("order not found");
        }
        payments.entrySet().removeIf(entry -> Objects.equals(entry.getValue().orderNo(), orderNo));
        paymentCallbackLogs.entrySet().removeIf(entry -> Objects.equals(entry.getValue().orderNo(), orderNo));
        refunds.entrySet().removeIf(entry -> Objects.equals(entry.getValue().orderNo(), orderNo));
        smsLogs.entrySet().removeIf(entry -> Objects.equals(entry.getValue().orderNo(), orderNo));
        cards.replaceAll((id, card) -> Objects.equals(card.orderNo(), orderNo)
            ? new CardSecret(
                card.id(),
                card.goodsId(),
                card.cardNo(),
                card.secret(),
                card.content(),
                card.preview(),
                "UNSOLD",
                null,
                card.importedAt(),
                null,
                card.cardKindId()
            )
            : card
        );
        appendOperation("ORDER_DELETE", "ORDER", orderNo, "manual delete order");
    }

    public synchronized GoodsItem createGoods(CreateGoodsRequest request) {
        Long id = allocateIncrementingId(goodsId, maxGoodsId());
        Long categoryId = request.categoryId() == null ? 1L : request.categoryId();
        CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        GoodsType type = request.type() == null ? GoodsType.CARD : request.type();
        Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), null);
        List<String> accountTypes = validateEnabledRechargeFieldCodes(request.accountTypes());
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

    public synchronized GoodsItem updateGoods(Long id, CreateGoodsRequest request) {
        GoodsItem current = findGoodsSnapshot(id).orElse(null);
        if (current == null) {
            throw new IllegalArgumentException("goods not found");
        }
        Long categoryId = request.categoryId() == null ? current.categoryId() : request.categoryId();
        CategoryItem category = findCategorySnapshot(categoryId).orElse(null);
        GoodsType type = request.type() == null ? current.type() : request.type();
        Long boundCardKindId = normalizeGoodsCardKindId(type, request.cardKindId(), current.cardKindId());
        List<String> accountTypes = request.accountTypes() == null ? current.accountTypes() : validateEnabledRechargeFieldCodes(request.accountTypes());
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

    public synchronized OrderItem createOrder(CreateOrderRequest request) {
        return createOrder(request, 90001L);
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request, Long userId) {
        return createOrder(request, userId, "");
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request, Long userId, String orderIp) {
        return createOrder(request, userId, orderIp, null);
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request, Long userId, String orderIp, String defaultTerminal) {
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
        String sourcePlatform = orderSource(request, defaultTerminal);
        validateGoodsSalePlatform(item, sourcePlatform);
        validateOrderPermission(user);
        validatePriceLimitPermission(user, item);
        validateRechargeAccount(item, request.rechargeAccount());

        OffsetDateTime now = OffsetDateTime.now();
        String orderNo = nextOrderNo(user.id());
        OrderItem order = buildOrder(
            orderNo,
            user,
            item,
            quantity,
            request,
            sourcePlatform,
            normalizeClientIp(orderIp),
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
            throw new IllegalStateException("订单当前状态不可重复支付");
        }

        OffsetDateTime paidAt = OffsetDateTime.now();
        String method = normalizePayMethod(request == null ? "" : request.payMethod());
        String terminal = normalizeTerminal(request == null ? "" : request.terminal());
        PaymentChannelItem channel = requireUsablePaymentChannel(method, terminal);
        method = channel.code();
        if ("balance".equals(method)) {
            UserItem user = requiredUser(order.userId());
            BigDecimal currentBalance = user.balance() == null ? BigDecimal.ZERO : user.balance();
            if (currentBalance.compareTo(order.payAmount()) < 0) {
                throw new IllegalStateException("余额不足，请先充值");
            }
            UserItem debited = new UserItem(
                user.id(),
                user.avatar(),
                user.mobile(),
                user.email(),
                user.nickname(),
                user.groupId(),
                groupName(user.groupId()),
                currentBalance.subtract(order.payAmount()),
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
            users.put(user.id(), debited);
            persistUserSnapshot(debited);
        } else if (prodProfile) {
            throw new IllegalStateException("生产环境不允许模拟支付成功，请接入真实支付网关或使用余额支付");
        } else if (!"MOCK".equalsIgnoreCase(defaultText(systemSetting.paymentMode(), "MOCK"))) {
            throw new IllegalStateException("真实微信/支付宝支付尚未完成网关下单接入，请先使用余额支付或切回模拟支付模式");
        }
        PaymentItem payment = createSuccessfulPayment(order, method, paidAt);
        appendOperation("PAYMENT_CREATE", "PAYMENT", payment.paymentNo(), method + " payment succeeded");
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
            if (supplier.balance() != null && supplier.balance().compareTo(order.payAmount()) < 0) {
                attempts.add(attempt(channel, "FAILED", "供应商余额不足"));
                continue;
            }

            try {
                ProcurementSubmitResult result = submitProcurementOrder(order, channel, supplier);
                attempts.add(attempt(channel, "SUCCESS", result.attemptMessage()));
                return order.withProcurementResult(
                    OrderStatus.PROCURING,
                    result.deliveryItems(),
                    List.copyOf(attempts),
                    trigger + "成功：已提交到 " + channel.supplierName() + "，等待上游处理",
                    order.paidAt(),
                    null
                );
            } catch (RuntimeException ex) {
                attempts.add(attempt(channel, "FAILED", "提交失败：" + ex.getMessage()));
            }
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

    private ProcurementSubmitResult submitProcurementOrder(
        OrderItem order,
        GoodsChannelItem channel,
        SupplierItem supplier
    ) {
        if (!isApiSupplier(supplier) && !isFuluSupplier(supplier) && !isFengzhushouSupplier(supplier) && !isChengquanSupplier(supplier) && !isFanchenSupplier(supplier) && !isJingzhaoSupplier(supplier)) {
            throw new IllegalStateException("供应商暂不支持真实下单");
        }
        if (isPlaceholderBaseUrl(supplier.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能真实下单");
        }
        if (isKakayunSupplier(supplier)) {
            return submitKakayunProcurementOrder(order, channel, supplier);
        }
        if (isFuluSupplier(supplier)) {
            return submitFuluProcurementOrder(order, channel, supplier);
        }
        if (isFengzhushouSupplier(supplier)) {
            return submitFengzhushouProcurementOrder(order, channel, supplier);
        }
        if (isChengquanSupplier(supplier)) {
            return submitChengquanProcurementOrder(order, channel, supplier);
        }
        if (isFanchenSupplier(supplier)) {
            return submitFanchenProcurementOrder(order, channel, supplier);
        }
        if (isJingzhaoSupplier(supplier)) {
            return submitJingzhaoProcurementOrder(order, channel, supplier);
        }
        JsonNode root = submitKasushouOrder(order, channel, supplier);
        ensureKasushouOk(root, "order submit");
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(
            textValue(data, "ordersn", "order_sn", "orderNo", "order_no"),
            textValue(root, "ordersn", "order_sn", "orderNo", "order_no"),
            ""
        );
        String externalOrderNo = firstText(
            textValue(data, "external_orderno", "externalOrderNo", "external_order_no"),
            order.orderNo(),
            ""
        );
        String totalPrice = textValue(data, "total_price", "totalPrice", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        if (StringUtils.hasText(externalOrderNo)) {
            deliveryItems.add("外部订单号：" + externalOrderNo);
        }
        String priceText = StringUtils.hasText(totalPrice) ? "，上游金额：" + totalPrice : "";
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + priceText;
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private ProcurementSubmitResult submitKakayunProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitKakayunOrder(order, channel, supplier);
        int code = intValue(root.path("code"), -1);
        if (code != 1 && code != 9999) {
            String message = textValue(root, "msg", "message", "error");
            throw new IllegalStateException("kakayun order submit failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(
            textValue(data, "orderno", "orderNo", "order_no"),
            textValue(root, "orderno", "orderNo", "order_no"),
            ""
        );
        String externalOrderNo = firstText(
            textValue(data, "usorderno", "usOrderNo", "external_order_no"),
            order.orderNo(),
            ""
        );
        String totalPrice = textValue(data, "money", "total_price", "totalPrice", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        if (StringUtils.hasText(externalOrderNo)) {
            deliveryItems.add("外部订单号：" + externalOrderNo);
        }
        String prefix = code == 9999 ? "上游返回处理中，已提交待查单" : "上游已接单，等待处理";
        String priceText = StringUtils.hasText(totalPrice) ? "，上游金额：" + totalPrice : "";
        String attemptMessage = prefix
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + priceText;
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private JsonNode submitKasushouOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateKasushouCredentials(supplier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", kasushouGoodsId(channel.supplierGoodsId()));
        if (StringUtils.hasText(supplier.callbackUrl())) {
            body.put("url", supplier.callbackUrl().trim());
        }
        body.put("external_orderno", order.orderNo());
        body.put("mark", defaultText(order.buyerRemark(), ""));
        body.put("quantity", order.quantity() == null ? 1 : order.quantity());

        Map<String, Object> attach = new LinkedHashMap<>();
        if (StringUtils.hasText(order.rechargeAccount())) {
            attach.put("recharge_account", order.rechargeAccount().trim());
        }
        if (!attach.isEmpty()) {
            body.put("attach", attach);
        }
        return kasushouPostJson(supplier, "/api/v1/order/buy", body, "order submit");
    }

    private JsonNode submitKakayunOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateKakayunCredentials(supplier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsid", kasushouGoodsId(channel.supplierGoodsId()));
        body.put("buynum", order.quantity() == null ? 1 : order.quantity());
        body.put("usorderno", order.orderNo());
        body.put("maxmoney", order.payAmount());
        if (StringUtils.hasText(order.rechargeAccount())) {
            body.put("attach", order.rechargeAccount().trim());
        }
        if (StringUtils.hasText(supplier.callbackUrl())) {
            body.put("callbackurl", supplier.callbackUrl().trim());
        }
        return kakayunPostJson(supplier, "/dockapiv3/order/create", body, "order submit");
    }

    private ProcurementSubmitResult submitFuluProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitFuluOrder(order, channel, supplier);
        ensureFuluOk(root, "order submit");
        verifyFuluResponseSign(root, supplier, "order submit");
        FuluOrderStatus upstream = fuluOrderStatusFromResult(root.path("result").asText(""));
        List<String> deliveryItems = mergedDeliveryItems(List.of(), upstream);
        String priceText = upstream.totalPrice() == null ? "" : "，上游金额：" + upstream.totalPrice();
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstream.upstreamOrderNo()) ? "，上游订单号：" + upstream.upstreamOrderNo() : "")
            + priceText;
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private JsonNode submitFuluOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateFuluCredentials(supplier);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        biz.put("customer_order_no", order.orderNo());
        if (StringUtils.hasText(order.rechargeAccount())) {
            biz.put("charge_account", order.rechargeAccount().trim());
        }
        biz.put("buy_num", order.quantity() == null ? 1 : order.quantity());
        if (StringUtils.hasText(order.orderIp())) {
            biz.put("charge_ip", order.orderIp().trim());
        }
        biz.put("customer_price", order.payAmount());
        return fuluPostJson(supplier, "order.notify", biz, "order submit");
    }

    private ProcurementSubmitResult submitFengzhushouProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitFengzhushouOrder(order, channel, supplier);
        int code = intValue(root.path("retcode"), -1);
        if (isFengzhushouPublicError(code)) {
            String message = textValue(root, "msg", "message", "error");
            throw new IllegalStateException("fengzhushou order submit failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
        String prefix = code == 9999 || code != 0 ? "上游返回处理中，已提交待查单" : "上游已收单，等待处理";
        return new ProcurementSubmitResult(
            List.of("外部订单号：" + order.orderNo()),
            prefix + "，外部订单号：" + order.orderNo()
        );
    }

    private JsonNode submitFengzhushouOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateFengzhushouCredentials(supplier);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", fengzhushouProjectCode(supplier));
        body.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        body.put("skuCode", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("channelOrderNo", order.orderNo());
        body.put("account", defaultText(order.rechargeAccount(), "").trim());
        body.put("num", order.quantity() == null ? 1 : order.quantity());
        body.put("callbackUrl", defaultText(supplier.callbackUrl(), "").trim());
        body.put("skuPrice", order.payAmount());
        body.put("ext", defaultText(order.buyerRemark(), "").trim());
        body.put("sign", FengzhushouSignatureUtil.sign(body, fengzhushouSignKey(supplier)));
        return fengzhushouPostJson(supplier, "/fzs-stdopen-api/api/v1/sendgoods", body, "order submit");
    }

    private ProcurementSubmitResult submitChengquanProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitChengquanOrder(order, channel, supplier);
        ensureChengquanOk(root, "order submit");
        JsonNode data = root.path("data");
        String upstreamOrderNo = firstText(textValue(data, "order_no", "orderNo", "order_id", "orderId"), textValue(root, "order_no", "orderNo"), "");
        String status = firstText(textValue(data, "status", "order_status", "orderStatus"), "RECHARGE", "");
        String amount = firstText(textValue(data, "amount", "money", "price"), "", "");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        deliveryItems.add("外部订单号：" + order.orderNo());
        String attemptMessage = "上游已接单，状态：" + status
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + (StringUtils.hasText(amount) ? "，上游金额：" + amount : "");
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private JsonNode submitChengquanOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateChengquanCredentials(supplier);
        Map<String, Object> body = chengquanBaseParams(supplier);
        body.put("order_no", order.orderNo());
        body.put("recharge_number", defaultText(order.rechargeAccount(), "").trim());
        body.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("amount", order.quantity() == null ? 1 : order.quantity());
        body.put("version", "v1");
        if (StringUtils.hasText(supplier.callbackUrl())) {
            body.put("notify_url", supplier.callbackUrl().trim());
        }
        body.put("sign", ChengquanSignatureUtil.sign(body, chengquanSecret(supplier)));
        return chengquanPostJson(supplier, "/order/directCharge", body, "order submit");
    }

    private ProcurementSubmitResult submitFanchenProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitFanchenOrder(order, channel, supplier);
        String code = textValue(root, "resultno");
        if (!Set.of("0", "2").contains(code)) {
            throw new IllegalStateException("fanchen order submit failed: code=" + code
                + (StringUtils.hasText(textValue(root, "remark1", "msg", "message")) ? " message=" + textValue(root, "remark1", "msg", "message") : ""));
        }
        String upstreamOrderNo = textValue(root, "orderid", "orderId");
        String amount = textValue(root, "ordercash", "amount");
        List<String> deliveryItems = new ArrayList<>();
        if (StringUtils.hasText(upstreamOrderNo)) {
            deliveryItems.add("上游订单号：" + upstreamOrderNo);
        }
        deliveryItems.add("外部订单号：" + order.orderNo());
        String attemptMessage = "上游已接单，等待处理"
            + (StringUtils.hasText(upstreamOrderNo) ? "，上游订单号：" + upstreamOrderNo : "")
            + (StringUtils.hasText(amount) ? "，上游金额：" + amount : "");
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private JsonNode submitFanchenOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        validateFanchenCredentials(supplier);
        Map<String, Object> body = fanchenBaseParams(supplier);
        body.put("productid", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("num", String.valueOf(order.quantity() == null ? 1 : order.quantity()));
        body.put("areaid", "");
        body.put("serverid", "");
        body.put("account", defaultText(order.rechargeAccount(), "").trim());
        body.put("spordertime", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(ZonedDateTime.now(CHINA_ZONE)));
        body.put("sporderid", order.orderNo());
        body.put("sign", FanchenSignatureUtil.sign(
            body,
            List.of("userid", "productid", "num", "areaid", "serverid", "account", "spordertime", "sporderid"),
            fanchenKey(supplier)
        ));
        if (StringUtils.hasText(supplier.callbackUrl())) {
            body.put("back_url", supplier.callbackUrl().trim());
        }
        body.put("checkprice", order.payAmount());
        return fanchenPostJson(supplier, "/fcgameonlinepay.do", body, "order submit");
    }

    private ProcurementSubmitResult submitJingzhaoProcurementOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        JsonNode root = submitJingzhaoOrder(order, channel, supplier);
        ensureJingzhaoOk(root, "order submit");
        JsonNode data = root.path("data");
        JingzhaoOrderStatus upstream = jingzhaoOrderStatusFromNode(data, order.orderNo());
        List<String> deliveryItems = mergedDeliveryItems(List.of(), upstream);
        String attemptMessage = "上游已接单，状态：" + jingzhaoStatusLabel(upstream.status())
            + (StringUtils.hasText(upstream.upstreamOrderNo()) ? "，上游订单号：" + upstream.upstreamOrderNo() : "")
            + (upstream.totalPrice() == null ? "" : "，上游金额：" + upstream.totalPrice());
        return new ProcurementSubmitResult(List.copyOf(deliveryItems), attemptMessage);
    }

    private JsonNode submitJingzhaoOrder(OrderItem order, GoodsChannelItem channel, SupplierItem supplier) {
        Map<String, Object> body = jingzhaoBaseParams(supplier);
        body.put("product_id", defaultText(channel.supplierGoodsId(), "").trim());
        body.put("quantity", order.quantity() == null ? 1 : order.quantity());
        body.put("outer_order_id", order.orderNo());
        body.put("safe_cost", order.payAmount());
        if (StringUtils.hasText(order.rechargeAccount())) {
            body.put("recharge_account", order.rechargeAccount().trim());
        }
        if (StringUtils.hasText(supplier.callbackUrl())) {
            body.put("notify_url", supplier.callbackUrl().trim());
        }
        if (StringUtils.hasText(order.orderIp())) {
            body.put("client_ip", order.orderIp().trim());
        }
        body.put("sign", JingzhaoSignatureUtil.sign(body, jingzhaoKey(supplier)));
        return jingzhaoPostJson(supplier, "/api/buy", body, "order submit");
    }

    private Object kasushouGoodsId(String supplierGoodsId) {
        String value = defaultText(supplierGoodsId, "").trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("上游商品ID为空");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private record ProcurementSubmitResult(
        List<String> deliveryItems,
        String attemptMessage
    ) {
    }

    private OrderItem refreshUpstreamOrderStatusIfNeeded(OrderItem order, boolean strict) {
        if (order == null || order.goodsType() != GoodsType.DIRECT || order.status() != OrderStatus.PROCURING) {
            return order;
        }
        List<ChannelAttemptItem> attempts = order.channelAttempts();
        if (attempts == null || attempts.isEmpty()) {
            return order;
        }
        ChannelAttemptItem successAttempt = attempts.stream()
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
        if (successAttempt == null || successAttempt.supplierId() == null) {
            return order;
        }
        SupplierItem supplier = findSupplierSnapshot(successAttempt.supplierId()).orElse(null);
        if (supplier == null) {
            if (strict) {
                throw new IllegalStateException("未找到订单对应的上游供应商");
            }
            return order;
        }
        if (!isApiSupplier(supplier) && !isFuluSupplier(supplier) && !isFengzhushouSupplier(supplier) && !isChengquanSupplier(supplier) && !isFanchenSupplier(supplier) && !isJingzhaoSupplier(supplier)) {
            if (strict) {
                throw new IllegalStateException("该上游供应商暂不支持刷新回调信息");
            }
            return order;
        }
        if (isPlaceholderBaseUrl(supplier.baseUrl())) {
            if (strict) {
                throw new IllegalStateException("该上游供应商地址是占位地址，无法刷新真实回调信息");
            }
            return order;
        }
        try {
            if (isFuluSupplier(supplier)) {
                FuluOrderStatus upstream = fetchFuluOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = fuluLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = fuluDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            if (isChengquanSupplier(supplier)) {
                ChengquanOrderStatus upstream = fetchChengquanOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = chengquanLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = chengquanDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            if (isFanchenSupplier(supplier)) {
                FanchenOrderStatus upstream = fetchFanchenOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = fanchenLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = fanchenDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            if (isJingzhaoSupplier(supplier)) {
                JingzhaoOrderStatus upstream = fetchJingzhaoOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = jingzhaoLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = jingzhaoDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            if (isFengzhushouSupplier(supplier)) {
                FengzhushouOrderStatus upstream = fetchFengzhushouOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = fengzhushouLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = fengzhushouDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            if (isKakayunSupplier(supplier)) {
                KakayunOrderStatus upstream = fetchKakayunOrderStatus(order, supplier);
                GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
                ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
                List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
                int index = nextAttempts.lastIndexOf(successAttempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichedAttempt);
                }
                OrderStatus nextStatus = kakayunLocalOrderStatus(upstream.status(), order.status());
                OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
                List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
                String message = kakayunDeliveryMessage(upstream);
                OrderItem next = order.withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
                orders.put(next.orderNo(), next);
                persistOrderSnapshot(next);
                if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                    publishOrder(next);
                }
                return next;
            }
            KasushouOrderStatus upstream = fetchKasushouOrderStatus(order, supplier);
            GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
            ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
            List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
            int index = nextAttempts.lastIndexOf(successAttempt);
            if (index >= 0) {
                nextAttempts.set(index, enrichedAttempt);
            }
            OrderStatus nextStatus = kasushouLocalOrderStatus(upstream.status(), order.status());
            OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
            List<String> deliveryItems = mergedDeliveryItems(order.deliveryItems(), upstream);
            String message = kasushouDeliveryMessage(upstream);
            OrderItem next = order.withProcurementResult(
                nextStatus,
                deliveryItems,
                List.copyOf(nextAttempts),
                message,
                order.paidAt(),
                deliveredAt
            );
            orders.put(next.orderNo(), next);
            persistOrderSnapshot(next);
            if (nextStatus != order.status() || !Objects.equals(message, order.deliveryMessage())) {
                publishOrder(next);
            }
            return next;
        } catch (RuntimeException ex) {
            if (strict) {
                throw ex;
            }
            return order;
        }
    }

    private KasushouOrderStatus fetchKasushouOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("external_orderno", order.orderNo());
        body.put("ordersn", "");
        body.put("day", "0");
        JsonNode root = kasushouPostJson(supplier, "/api/v1/order/info", body, "order info sync");
        ensureKasushouOk(root, "order info sync");
        JsonNode data = root.path("data");
        JsonNode node = data.isArray() && data.size() > 0 ? data.get(0) : data;
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("kasushou order info sync failed: data is empty");
        }
        List<String> cards = new ArrayList<>();
        JsonNode cardList = node.path("card_list");
        if (cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = textValue(card, "card_no", "cardNo");
                String cardPassword = textValue(card, "card_password", "cardPassword", "password");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        return new KasushouOrderStatus(
            textValue(node, "ordersn", "order_sn", "orderNo", "order_no"),
            textValue(node, "external_orderno", "externalOrderNo", "external_order_no"),
            intValue(node.path("status"), 0),
            textValue(node, "recharge_hints", "rechargeHints", "message", "msg"),
            optionalDecimalValue(node, "total_price", "totalPrice", "amount"),
            List.copyOf(cards),
            abbreviate(node.toString(), 1200)
        );
    }

    private KakayunOrderStatus fetchKakayunOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usorderno", order.orderNo());
        JsonNode root = kakayunPostJson(supplier, "/dockapiv3/order/get", body, "order info sync");
        ensureKakayunOk(root, "order info sync");
        JsonNode data = root.path("data");
        JsonNode node = data.isArray() && data.size() > 0 ? data.get(0) : data;
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("kakayun order info sync failed: data is empty");
        }
        List<String> cards = new ArrayList<>();
        JsonNode cardList = firstExisting(node, "cards", "cardlist", "cardList");
        if (cardList != null && cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = textValue(card, "card_no", "cardNo", "cardno");
                String cardPassword = textValue(card, "card_pwd", "cardPwd", "card_password", "password");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        } else if (cardList != null && StringUtils.hasText(cardList.asText())) {
            cards.add(cardList.asText());
        }
        return new KakayunOrderStatus(
            textValue(node, "orderno", "orderNo", "order_no"),
            firstText(textValue(node, "usorderno", "usOrderNo"), order.orderNo(), ""),
            intValue(node.path("status"), 0),
            firstText(textValue(node, "receipt", "refundreceipt", "message", "msg"), textValue(root, "msg", "message"), ""),
            optionalDecimalValue(node, "money", "total_price", "amount"),
            List.copyOf(cards),
            abbreviate(node.toString(), 1200)
        );
    }

    private FuluOrderStatus fetchFuluOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("customer_order_no", order.orderNo());
        JsonNode root = fuluPostJson(supplier, "order.query", biz, "order info sync");
        int code = intValue(root.path("code"), -1);
        if (code == 4011 || code == 5000) {
            String message = firstText(textValue(root, "msg", "message", "sub_msg"), "上游暂未返回最终状态", "");
            return new FuluOrderStatus(
                "",
                order.orderNo(),
                "",
                "",
                2,
                message,
                null,
                List.of(),
                abbreviate(root.toString(), 1200)
            );
        }
        ensureFuluOk(root, "order info sync");
        verifyFuluResponseSign(root, supplier, "order info sync");
        return fuluOrderStatusFromResult(root.path("result").asText(""));
    }

    private FengzhushouOrderStatus fetchFengzhushouOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", fengzhushouProjectCode(supplier));
        body.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        body.put("channelOrderNo", order.orderNo());
        body.put("sign", FengzhushouSignatureUtil.sign(body, fengzhushouSignKey(supplier)));
        JsonNode root = fengzhushouPostJson(supplier, "/fzs-stdopen-api/api/v1/queryorder", body, "order info sync");
        int code = intValue(root.path("retcode"), -1);
        if (code == 9999 || code == 3000) {
            return new FengzhushouOrderStatus(
                "",
                order.orderNo(),
                code,
                firstText(textValue(root, "msg", "message"), "上游暂未返回最终状态", ""),
                null,
                abbreviate(root.toString(), 1200)
            );
        }
        JsonNode data = root.path("data");
        return new FengzhushouOrderStatus(
            firstText(textValue(data, "orderNo", "order_no", "orderId"), textValue(root, "orderNo", "order_no"), ""),
            order.orderNo(),
            code,
            textValue(root, "msg", "message"),
            optionalDecimalValue(data, "skuPrice", "price", "amount", "totalPrice"),
            abbreviate(root.toString(), 1200)
        );
    }

    private ChengquanOrderStatus fetchChengquanOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = chengquanBaseParams(supplier);
        body.put("order_no", order.orderNo());
        body.put("sign", ChengquanSignatureUtil.sign(body, chengquanSecret(supplier)));
        JsonNode root = chengquanPostJson(supplier, "/order/get", body, "order info sync");
        ensureChengquanOk(root, "order info sync");
        JsonNode data = root.path("data");
        return new ChengquanOrderStatus(
            firstText(textValue(data, "order_no", "orderNo", "order_id", "orderId"), order.orderNo(), ""),
            order.orderNo(),
            firstText(textValue(data, "status", "order_status", "orderStatus"), textValue(root, "status"), "RECHARGE"),
            firstText(textValue(data, "message", "msg", "remark"), textValue(root, "message", "msg"), ""),
            optionalDecimalValue(data, "amount", "money", "price"),
            abbreviate(root.toString(), 1200)
        );
    }

    private FanchenOrderStatus fetchFanchenOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = fanchenBaseParams(supplier);
        body.put("sporderid", order.orderNo());
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid", "sporderid"), fanchenKey(supplier)));
        JsonNode root = fanchenPostJson(supplier, "/fcsearchpay.do", body, "order info sync");
        String code = textValue(root, "resultno");
        return new FanchenOrderStatus(
            textValue(root, "orderid", "orderId"),
            firstText(textValue(root, "sporderid", "sporderId"), order.orderNo(), ""),
            code,
            firstText(textValue(root, "remark1", "msg", "message"), textValue(root, "productname", "productName"), ""),
            optionalDecimalValue(root, "ordercash", "amount"),
            fanchenCards(root),
            abbreviate(root.toString(), 1200)
        );
    }

    private JingzhaoOrderStatus fetchJingzhaoOrderStatus(OrderItem order, SupplierItem supplier) {
        Map<String, Object> body = jingzhaoBaseParams(supplier);
        body.put("outer_order_id", order.orderNo());
        body.put("sign", JingzhaoSignatureUtil.sign(body, jingzhaoKey(supplier)));
        JsonNode root = jingzhaoPostJson(supplier, "/api/outer-order", body, "order info sync");
        ensureJingzhaoOk(root, "order info sync");
        return jingzhaoOrderStatusFromNode(root.path("data"), order.orderNo());
    }

    private JingzhaoOrderStatus jingzhaoOrderStatusFromNode(JsonNode node, String fallbackExternalOrderNo) {
        JsonNode safeNode = node == null || node.isMissingNode() || node.isNull() ? OBJECT_MAPPER.createObjectNode() : node;
        List<String> cards = new ArrayList<>();
        JsonNode cardList = firstExisting(safeNode, "cards", "cardList", "card_list");
        if (cardList != null && cardList.isArray()) {
            for (JsonNode card : cardList) {
                String cardNo = firstText(textValue(card, "card_no", "cardNo", "no"), "", "");
                String cardPassword = firstText(textValue(card, "card_password", "cardPassword", "password"), "", "");
                String expiredAt = textValue(card, "expired_at", "expiredAt");
                String line = StringUtils.hasText(cardNo)
                    ? "卡号：" + cardNo + (StringUtils.hasText(cardPassword) ? " 卡密：" + cardPassword : "")
                    : (StringUtils.hasText(cardPassword) ? "卡密：" + cardPassword : "");
                if (StringUtils.hasText(line) && StringUtils.hasText(expiredAt)) {
                    line += " 有效期：" + expiredAt;
                }
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        JsonNode ticketList = firstExisting(safeNode, "tickets", "ticketList", "ticket_list");
        if (ticketList != null && ticketList.isArray()) {
            for (JsonNode ticket : ticketList) {
                String value = firstText(textValue(ticket, "ticket", "url"), textValue(ticket, "no"), "");
                String expiredAt = textValue(ticket, "expired_at", "expiredAt");
                String line = StringUtils.hasText(value) ? "卡券：" + value : "";
                if (StringUtils.hasText(line) && StringUtils.hasText(expiredAt)) {
                    line += " 有效期：" + expiredAt;
                }
                if (StringUtils.hasText(line)) {
                    cards.add(line);
                }
            }
        }
        return new JingzhaoOrderStatus(
            firstText(textValue(safeNode, "order_id", "orderId", "id"), "", ""),
            firstText(textValue(safeNode, "outer_order_id", "outerOrderId"), fallbackExternalOrderNo, ""),
            intValue(firstExisting(safeNode, "state", "status"), 501),
            firstText(textValue(safeNode, "state_info", "stateInfo", "recharge_info", "rechargeInfo", "message", "msg"), "", ""),
            optionalDecimalValue(safeNode, "total_price", "totalPrice", "product_price", "productPrice"),
            List.copyOf(cards),
            abbreviate(safeNode.toString(), 1200)
        );
    }

    private List<String> fanchenCards(JsonNode root) {
        JsonNode cardsNode = firstExisting(root, "cards", "cardList", "card_list");
        if (cardsNode == null || !cardsNode.isArray()) {
            return List.of();
        }
        List<String> cards = new ArrayList<>();
        for (JsonNode card : cardsNode) {
            String cardNo = textValue(card, "cardno", "cardNo", "card_no");
            String cardPsw = textValue(card, "cardpsw", "cardPsw", "card_password", "password");
            String effectTime = textValue(card, "effecttime", "effectTime", "expire_time");
            String line = StringUtils.hasText(cardNo)
                ? "卡号：" + cardNo + (StringUtils.hasText(cardPsw) ? " 卡密：" + cardPsw : "")
                : (StringUtils.hasText(cardPsw) ? "卡密：" + cardPsw : "");
            if (StringUtils.hasText(line) && StringUtils.hasText(effectTime)) {
                line += " 有效期：" + effectTime;
            }
            if (StringUtils.hasText(line)) {
                cards.add(line);
            }
        }
        return List.copyOf(cards);
    }

    private Optional<GoodsIntegrationItem> upstreamGoodsSnapshot(SupplierItem supplier, String supplierGoodsId) {
        if (supplier == null || !StringUtils.hasText(supplierGoodsId)) {
            return Optional.empty();
        }
        try {
            RemoteGoodsItem remote = fetchRemoteGoodsSnapshot(supplier, supplierGoodsId, true);
            return Optional.of(remoteGoodsIntegration(supplier, remote));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        KasushouOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = kasushouStatusLabel(upstream.status());
        String localStatus = upstream.status() == 3 ? "DELIVERED"
            : (upstream.status() == 4 || upstream.status() == 5 || upstream.status() == -1 ? "FAILED" : "PROCURING");
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            localStatus,
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        KakayunOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = kakayunStatusLabel(upstream.status());
        OrderStatus nextStatus = kakayunLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        FuluOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = fuluStatusLabel(upstream.status());
        OrderStatus nextStatus = fuluLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = firstText(upstream.productName(), remote == null ? "" : remote.supplierGoodsName(), attempt.supplierGoodsName());
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        FengzhushouOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = fengzhushouStatusLabel(upstream.status());
        OrderStatus nextStatus = fengzhushouLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        ChengquanOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = chengquanStatusLabel(upstream.status());
        OrderStatus nextStatus = chengquanLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        FanchenOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = fanchenStatusLabel(upstream.status());
        OrderStatus nextStatus = fanchenLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        JingzhaoOrderStatus upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = jingzhaoStatusLabel(upstream.status());
        OrderStatus nextStatus = jingzhaoLocalOrderStatus(upstream.status(), OrderStatus.PROCURING);
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        String goodsName = remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName();
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            attempt.supplierName(),
            attempt.supplierGoodsId(),
            goodsName,
            price,
            upstreamStatus,
            upstreamStatus,
            callbackMessage,
            upstream.rawResponse(),
            attempt.priority(),
            nextStatus.name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private OrderStatus kasushouLocalOrderStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 3 -> OrderStatus.DELIVERED;
            case 4, 5, -1 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private String kasushouStatusLabel(int status) {
        return switch (status) {
            case 1 -> "PENDING";
            case 2 -> "PROCESSING";
            case 3 -> "DELIVERED";
            case 4 -> "CANCELLED";
            case 5 -> "REFUNDED";
            case -1 -> "UNPAID";
            default -> "UNKNOWN";
        };
    }

    private String kasushouDeliveryMessage(KasushouOrderStatus upstream) {
        String statusLabel = switch (upstream.status()) {
            case 1 -> "上游等待处理";
            case 2 -> "上游正在处理";
            case 3 -> "上游交易成功";
            case 4 -> "上游已取消交易";
            case 5 -> "上游已退款";
            case -1 -> "上游未支付";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus kakayunLocalOrderStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 5 -> OrderStatus.DELIVERED;
            case 2, 4 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private String kakayunStatusLabel(int status) {
        return switch (status) {
            case 0 -> "PAID";
            case 1 -> "PAID_OR_CARD_DONE";
            case 2 -> "UNPAID";
            case 3 -> "PROCESSING";
            case 4 -> "FAILED";
            case 5 -> "DELIVERED";
            default -> "UNKNOWN";
        };
    }

    private String kakayunDeliveryMessage(KakayunOrderStatus upstream) {
        String statusLabel = switch (upstream.status()) {
            case 0, 1 -> "上游已受理";
            case 2 -> "上游未付款或失败";
            case 3 -> "上游正在处理";
            case 4 -> "上游处理失败";
            case 5 -> "上游交易成功";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus fuluLocalOrderStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 3 -> OrderStatus.DELIVERED;
            case 4 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private String fuluStatusLabel(int status) {
        return switch (status) {
            case 1 -> "UNTREATED";
            case 2 -> "PROCESSING";
            case 3 -> "SUCCESS";
            case 4 -> "FAIL";
            default -> "UNKNOWN";
        };
    }

    private String fuluDeliveryMessage(FuluOrderStatus upstream) {
        String statusLabel = switch (upstream.status()) {
            case 1 -> "上游未处理";
            case 2 -> "上游充值中";
            case 3 -> "上游充值成功";
            case 4 -> "上游充值失败";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus fengzhushouLocalOrderStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 1 -> OrderStatus.DELIVERED;
            case 9, 2000, 3100, 3104, 3999, 4001, 4002 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private boolean isFengzhushouPublicError(int code) {
        return code == 102 || code == 103 || code == 107 || code == 400 || code == 1000;
    }

    private String fengzhushouStatusLabel(int status) {
        return switch (status) {
            case 0 -> "PROCESSING";
            case 1 -> "SUCCESS";
            case 9 -> "FAIL";
            case 2000 -> "SKU_OFFLINE";
            case 3000 -> "ORDER_NOT_FOUND";
            case 3100 -> "ACCOUNT_RESTRICTED";
            case 3104 -> "PRICE_MISMATCH";
            case 3999 -> "ORDER_CREATE_FAILED";
            case 4001 -> "DEDUCT_FAILED";
            case 4002 -> "BALANCE_NOT_ENOUGH";
            case 9999 -> "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    private String fengzhushouDeliveryMessage(FengzhushouOrderStatus upstream) {
        String statusLabel = switch (upstream.status()) {
            case 0 -> "上游发货中";
            case 1 -> "上游发货成功";
            case 9 -> "上游发货失败";
            case 2000 -> "上游单品不存在或已下架";
            case 3000 -> "上游暂未查到订单";
            case 3100 -> "充值账号无法购买此商品";
            case 3104 -> "上游价格不匹配";
            case 3999 -> "上游订单生成失败";
            case 4001 -> "上游扣款失败";
            case 4002 -> "上游账户余额不足";
            case 9999 -> "上游系统内部错误，状态待确认";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus chengquanLocalOrderStatus(String upstreamStatus, OrderStatus fallback) {
        String normalized = normalize(upstreamStatus);
        if ("success".equals(normalized)) {
            return OrderStatus.DELIVERED;
        }
        if ("failure".equals(normalized) || "fail".equals(normalized)) {
            return OrderStatus.FAILED;
        }
        return fallback == null ? OrderStatus.PROCURING : fallback;
    }

    private String chengquanStatusLabel(String status) {
        String normalized = normalize(status);
        if ("success".equals(normalized)) return "SUCCESS";
        if ("failure".equals(normalized) || "fail".equals(normalized)) return "FAILURE";
        if ("recharge".equals(normalized)) return "RECHARGE";
        return StringUtils.hasText(status) ? status : "UNKNOWN";
    }

    private String chengquanDeliveryMessage(ChengquanOrderStatus upstream) {
        String normalized = normalize(upstream.status());
        String statusLabel = switch (normalized) {
            case "success" -> "上游充值成功";
            case "failure", "fail" -> "上游充值失败";
            case "recharge" -> "上游充值中";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus fanchenLocalOrderStatus(String upstreamStatus, OrderStatus fallback) {
        return switch (defaultText(upstreamStatus, "").trim()) {
            case "1" -> OrderStatus.DELIVERED;
            case "9" -> OrderStatus.FAILED;
            case "5007" -> fallback == null ? OrderStatus.PROCURING : fallback;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private String fanchenStatusLabel(String status) {
        return switch (defaultText(status, "").trim()) {
            case "0" -> "WAITING";
            case "1" -> "SUCCESS";
            case "2" -> "PROCESSING";
            case "9" -> "FAILED_REFUNDED";
            case "5007" -> "ORDER_NOT_FOUND";
            default -> StringUtils.hasText(status) ? status : "UNKNOWN";
        };
    }

    private String fanchenDeliveryMessage(FanchenOrderStatus upstream) {
        String statusLabel = switch (defaultText(upstream.status(), "").trim()) {
            case "0", "2" -> "上游充值中";
            case "1" -> "上游充值成功";
            case "9" -> "上游充值失败已退款";
            case "5007" -> "上游暂未查到订单";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private OrderStatus jingzhaoLocalOrderStatus(int upstreamStatus, OrderStatus fallback) {
        return switch (upstreamStatus) {
            case 200 -> OrderStatus.DELIVERED;
            case 500 -> OrderStatus.FAILED;
            default -> fallback == null ? OrderStatus.PROCURING : fallback;
        };
    }

    private String jingzhaoStatusLabel(int status) {
        return switch (status) {
            case 100 -> "WAITING";
            case 101 -> "PROCESSING";
            case 200 -> "SUCCESS";
            case 500 -> "FAILED";
            case 501 -> "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    private String jingzhaoDeliveryMessage(JingzhaoOrderStatus upstream) {
        String statusLabel = switch (upstream.status()) {
            case 100 -> "上游等待发货";
            case 101 -> "上游正在充值";
            case 200 -> "上游交易成功";
            case 500 -> "上游交易失败";
            case 501 -> "上游状态未知";
            default -> "上游状态未知";
        };
        String hints = StringUtils.hasText(upstream.hints()) ? "：" + upstream.hints() : "";
        return statusLabel + hints;
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, KasushouOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        items.addAll(upstream.cards());
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, KakayunOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        items.addAll(upstream.cards());
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, FuluOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        items.addAll(upstream.cards());
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, FengzhushouOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, ChengquanOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, FanchenOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        items.addAll(upstream.cards());
        return List.copyOf(items);
    }

    private List<String> mergedDeliveryItems(List<String> currentItems, JingzhaoOrderStatus upstream) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (currentItems != null) {
            items.addAll(currentItems);
        }
        if (StringUtils.hasText(upstream.upstreamOrderNo())) {
            items.add("上游订单号：" + upstream.upstreamOrderNo());
        }
        if (StringUtils.hasText(upstream.externalOrderNo())) {
            items.add("外部订单号：" + upstream.externalOrderNo());
        }
        if (upstream.totalPrice() != null) {
            items.add("上游金额：" + upstream.totalPrice());
        }
        if (StringUtils.hasText(upstream.hints())) {
            items.add("上游结果：" + upstream.hints());
        }
        items.addAll(upstream.cards());
        return List.copyOf(items);
    }

    private record KasushouOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        int status,
        String hints,
        BigDecimal totalPrice,
        List<String> cards,
        String rawResponse
    ) {
    }

    private record KakayunOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        int status,
        String hints,
        BigDecimal totalPrice,
        List<String> cards,
        String rawResponse
    ) {
    }

    private record FuluOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        String productId,
        String productName,
        int status,
        String hints,
        BigDecimal totalPrice,
        List<String> cards,
        String rawResponse
    ) {
    }

    private record FengzhushouOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        int status,
        String hints,
        BigDecimal totalPrice,
        String rawResponse
    ) {
    }

    private record ChengquanOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        String status,
        String hints,
        BigDecimal totalPrice,
        String rawResponse
    ) {
    }

    private record FanchenOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        String status,
        String hints,
        BigDecimal totalPrice,
        List<String> cards,
        String rawResponse
    ) {
    }

    private record JingzhaoOrderStatus(
        String upstreamOrderNo,
        String externalOrderNo,
        int status,
        String hints,
        BigDecimal totalPrice,
        List<String> cards,
        String rawResponse
    ) {
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
        String normalizedOrderNo = defaultText(orderNo, "").trim();
        OrderItem order = orders.get(normalizedOrderNo);
        if (order == null) {
            order = persistentOrder(normalizedOrderNo).orElse(null);
            if (order != null) {
                orders.put(order.orderNo(), order);
            }
        }
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
        realtimeBroadcaster.publish(withLatestSupplierNames(order));
    }

    private OrderItem withLatestSupplierNames(OrderItem order) {
        if (order == null || order.channelAttempts() == null || order.channelAttempts().isEmpty()) {
            return order;
        }
        List<ChannelAttemptItem> nextAttempts = order.channelAttempts().stream()
            .map(this::withLatestSupplierName)
            .toList();
        if (Objects.equals(order.channelAttempts(), nextAttempts)) {
            return order;
        }
        return new OrderItem(
            order.orderNo(),
            order.userId(),
            order.buyerAccount(),
            order.goodsId(),
            order.goodsName(),
            order.goodsType(),
            order.platform(),
            order.orderIp(),
            order.orderIpLocation(),
            order.quantity(),
            order.unitPrice(),
            order.payAmount(),
            order.status(),
            order.rechargeAccount(),
            order.buyerRemark(),
            order.requestId(),
            order.paymentNo(),
            order.payMethod(),
            order.deliveryItems(),
            nextAttempts,
            order.deliveryMessage(),
            order.createdAt(),
            order.paidAt(),
            order.deliveredAt()
        );
    }

    private ChannelAttemptItem withLatestSupplierName(ChannelAttemptItem attempt) {
        if (attempt == null || attempt.supplierId() == null) {
            return attempt;
        }
        String latestName = findSupplierSnapshot(attempt.supplierId())
            .map(SupplierItem::name)
            .filter(StringUtils::hasText)
            .orElse(attempt.supplierName());
        if (Objects.equals(defaultText(latestName, ""), defaultText(attempt.supplierName(), ""))) {
            return attempt;
        }
        return new ChannelAttemptItem(
            attempt.channelId(),
            attempt.supplierId(),
            latestName,
            attempt.supplierGoodsId(),
            attempt.supplierGoodsName(),
            attempt.supplierPrice(),
            attempt.upstreamStatus(),
            attempt.callbackStatus(),
            attempt.callbackMessage(),
            attempt.rawResponse(),
            attempt.priority(),
            attempt.status(),
            attempt.message(),
            attempt.attemptedAt()
        );
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

    private Optional<SupplierItem> findSupplierSnapshot(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        SupplierItem memory = suppliers.get(id);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<SupplierItem> persistent = persistentSuppliers().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.id(), id))
            .findFirst();
        persistent.ifPresent(item -> suppliers.put(item.id(), item));
        return persistent;
    }

    private SupplierItem testKasushouConnection(SupplierItem item) {
        validateKasushouCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }

        JsonNode root = kasushouPostJson(item, "/api/v1/user/info", Map.of(), "test connection");
        ensureKasushouOk(root, "test connection");
        return item.withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshKasushouBalance(SupplierItem item) {
        validateKasushouCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
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
        Map<String, String> categoryNames = remoteCategoryNames(categories);
        String selectedCategoryId = cateId == null || cateId == 0 ? "" : String.valueOf(cateId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

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
            items.add(remoteGoodsItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName));
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

    private RemoteGoodsSyncResult fetchIntegratedRemoteGoods(SupplierItem item, Long cateId, String keyword, int page, int limit) {
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalArgumentException("供应商 API 地址仍是占位地址，不能拉取上游商品");
        }
        if (isKasushouSupplier(item)) {
            return fetchKasushouGoods(item, cateId, keyword, page, limit);
        }
        if (isKakayunSupplier(item)) {
            return fetchKakayunGoods(item, cateId, keyword, page, Math.min(limit, 100));
        }
        if (isChengquanSupplier(item)) {
            return fetchChengquanGoods(item, cateId, keyword, page, Math.min(limit, 100));
        }
        if (isFanchenSupplier(item)) {
            return fetchFanchenGoods(item, keyword, page, Math.min(limit, 100));
        }
        if (isJingzhaoSupplier(item)) {
            return fetchJingzhaoGoods(item, cateId, keyword, page, Math.min(limit, 100));
        }
        throw new IllegalArgumentException("supplier platformType is not supported");
    }

    private SupplierItem testKakayunConnection(SupplierItem item) {
        validateKakayunCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }
        JsonNode root = kakayunPostJson(item, "/dockapiv3/user/info", Map.of(), "test connection");
        ensureKakayunOk(root, "test connection");
        return item.withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshKakayunBalance(SupplierItem item) {
        validateKakayunCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        JsonNode root = kakayunPostJson(item, "/dockapiv3/user/info", Map.of(), "balance refresh");
        ensureKakayunOk(root, "balance refresh");
        return item.withBalance(kakayunBalance(root));
    }

    private RemoteGoodsSyncResult fetchKakayunGoods(SupplierItem item, Long groupId, String keyword, int page, int limit) {
        validateKakayunCredentials(item);

        JsonNode groupRoot = kakayunPostJson(item, "/dockapiv3/goods/group", Map.of(), "category sync");
        ensureKakayunOk(groupRoot, "category sync");
        List<Map<String, Object>> categories = kakayunCategories(groupRoot.path("data"));
        Map<String, String> categoryNames = remoteCategoryNames(categories);
        String selectedCategoryId = groupId == null || groupId == 0 ? "" : String.valueOf(groupId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page);
        body.put("limit", limit);
        if (StringUtils.hasText(keyword)) {
            body.put("goodsname", keyword.trim());
        }
        if (StringUtils.hasText(selectedCategoryId)) {
            body.put("groupid", selectedCategoryId);
        }
        JsonNode listRoot = kakayunPostJson(item, "/dockapiv3/goods/all", body, "goods list sync");
        ensureKakayunOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.isArray() ? data : firstExisting(data, "list", "goods", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("kakayun goods list sync failed: data list is missing");
        }
        int total = intValue(firstExisting(listRoot, "count", "total"), listNode.size());
        if (data.isObject()) {
            total = intValue(firstExisting(data, "count", "total"), total);
        }
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            items.add(kakayunRemoteGoodsItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName));
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
            "synced " + items.size() + " kakayun goods from remote total " + total
        );
    }

    private RemoteGoodsSyncResult fetchChengquanGoods(SupplierItem item, Long typeId, String keyword, int page, int limit) {
        validateChengquanCredentials(item);
        Map<String, Object> typeBody = chengquanBaseParams(item);
        typeBody.put("sign", ChengquanSignatureUtil.sign(typeBody, chengquanSecret(item)));
        JsonNode typeRoot = chengquanPostJson(item, "/coupon/type/list", typeBody, "category sync");
        ensureChengquanOk(typeRoot, "category sync");
        JsonNode typeList = firstExisting(typeRoot.path("data"), "list", "records", "items", "data");
        if (typeList == null || !typeList.isArray()) {
            typeList = typeRoot.path("data").isArray() ? typeRoot.path("data") : OBJECT_MAPPER.createArrayNode();
        }
        List<Map<String, Object>> categories = OBJECT_MAPPER.convertValue(typeList, LIST_MAP_TYPE);
        Map<String, String> categoryNames = remoteCategoryNames(categories);
        String selectedCategoryId = typeId == null || typeId == 0 ? "" : String.valueOf(typeId);
        String selectedCategoryName = categoryNames.getOrDefault(selectedCategoryId, "");

        Map<String, Object> body = chengquanBaseParams(item);
        body.put("page", page);
        body.put("page_size", limit);
        if (StringUtils.hasText(selectedCategoryId)) {
            body.put("type_id", selectedCategoryId);
        }
        body.put("sign", ChengquanSignatureUtil.sign(body, chengquanSecret(item)));
        JsonNode listRoot = chengquanPostJson(item, "/coupon/type/goods/list", body, "goods list sync");
        ensureChengquanOk(listRoot, "goods list sync");
        JsonNode data = listRoot.path("data");
        JsonNode listNode = data.isArray() ? data : firstExisting(data, "list", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("chengquan goods list sync failed: data list is missing");
        }
        int total = intValue(firstExisting(data, "total", "count"), listNode.size());
        List<RemoteGoodsItem> items = new ArrayList<>();
        for (JsonNode node : listNode) {
            RemoteGoodsItem remote = chengquanRemoteGoodsItem(item.id(), node, categoryNames, selectedCategoryId, selectedCategoryName);
            if (!StringUtils.hasText(keyword) || normalize(remote.goodsName()).contains(normalize(keyword)) || normalize(remote.supplierGoodsId()).contains(normalize(keyword))) {
                items.add(remote);
            }
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
            "synced " + items.size() + " chengquan goods from remote total " + total
        );
    }

    private RemoteGoodsSyncResult fetchFanchenGoods(SupplierItem item, String keyword, int page, int limit) {
        validateFanchenCredentials(item);
        Map<String, Object> body = fanchenBaseParams(item);
        body.put("productid", "");
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid", "productid"), fanchenKey(item)));
        JsonNode root = fanchenPostJson(item, "/fcuserproductprice.do", body, "goods list sync");
        ensureFanchenOk(root, "goods list sync", Set.of("1"));
        JsonNode listNode = firstExisting(root, "products", "data", "list", "items");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("fanchen goods list sync failed: products is missing");
        }
        List<RemoteGoodsItem> allItems = new ArrayList<>();
        Map<String, String> categoryNames = new LinkedHashMap<>();
        for (JsonNode node : listNode) {
            String categoryId = textValue(node, "category_id", "categoryId");
            String categoryName = textValue(node, "category_name", "categoryName");
            if (StringUtils.hasText(categoryId) && StringUtils.hasText(categoryName)) {
                categoryNames.put(categoryId, categoryName);
            }
            RemoteGoodsItem remote = fanchenRemoteGoodsItem(item.id(), node);
            if (!StringUtils.hasText(keyword) || normalize(remote.goodsName()).contains(normalize(keyword)) || normalize(remote.supplierGoodsId()).contains(normalize(keyword))) {
                allItems.add(remote);
            }
        }
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(allItems.size(), from + limit);
        List<RemoteGoodsItem> items = from >= allItems.size() ? List.of() : allItems.subList(from, to);
        List<Map<String, Object>> categories = categoryNames.entrySet().stream()
            .map(entry -> {
                Map<String, Object> category = new LinkedHashMap<>();
                category.put("id", entry.getKey());
                category.put("name", entry.getValue());
                return category;
            })
            .toList();
        OffsetDateTime syncedAt = OffsetDateTime.now();
        return new RemoteGoodsSyncResult(
            item.id(),
            syncedAt,
            allItems.size(),
            List.copyOf(items),
            categories,
            page,
            limit,
            "synced " + items.size() + " fanchen goods from remote total " + allItems.size()
        );
    }

    private RemoteGoodsSyncResult fetchJingzhaoGoods(SupplierItem item, Long ignoredCategoryId, String keyword, int page, int limit) {
        validateJingzhaoCredentials(item);
        Map<String, Object> body = jingzhaoBaseParams(item);
        body.put("sign", JingzhaoSignatureUtil.sign(body, jingzhaoKey(item)));
        JsonNode root = jingzhaoPostJson(item, "/api/product-list", body, "goods list sync");
        ensureJingzhaoOk(root, "goods list sync");
        JsonNode listNode = root.path("data").isArray() ? root.path("data") : firstExisting(root.path("data"), "list", "records", "items", "data");
        if (listNode == null || !listNode.isArray()) {
            throw new IllegalStateException("jingzhao goods list sync failed: data list is missing");
        }
        List<RemoteGoodsItem> allItems = new ArrayList<>();
        Map<String, String> categoriesByType = new LinkedHashMap<>();
        for (JsonNode node : listNode) {
            RemoteGoodsItem remote = jingzhaoRemoteGoodsItem(item.id(), node);
            categoriesByType.putIfAbsent(remote.goodsType(), jingzhaoGoodsTypeLabel(remote.goodsType()));
            if (!StringUtils.hasText(keyword) || normalize(remote.goodsName()).contains(normalize(keyword)) || normalize(remote.supplierGoodsId()).contains(normalize(keyword))) {
                allItems.add(remote);
            }
        }
        int from = Math.max(0, (page - 1) * limit);
        int to = Math.min(allItems.size(), from + limit);
        List<RemoteGoodsItem> items = from >= allItems.size() ? List.of() : allItems.subList(from, to);
        List<Map<String, Object>> categories = categoriesByType.entrySet().stream()
            .map(entry -> {
                Map<String, Object> category = new LinkedHashMap<>();
                category.put("id", entry.getKey());
                category.put("name", entry.getValue());
                return category;
            })
            .toList();
        OffsetDateTime syncedAt = OffsetDateTime.now();
        return new RemoteGoodsSyncResult(
            item.id(),
            syncedAt,
            allItems.size(),
            List.copyOf(items),
            categories,
            page,
            limit,
            "synced " + items.size() + " jingzhao goods from remote total " + allItems.size()
        );
    }

    private List<Map<String, Object>> kakayunCategories(JsonNode data) {
        JsonNode groupNode = data.isArray() ? data : firstExisting(data, "list", "groups", "records", "items", "data");
        if (groupNode == null || !groupNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (JsonNode node : groupNode) {
            String id = textValue(node, "id", "groupid", "groupId");
            String name = firstText(textValue(node, "groupaliasname", "groupAliasName"), textValue(node, "groupname", "groupName"), "");
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("id", id);
            category.put("name", name);
            category.put("brandId", textValue(node, "brandid", "brandId"));
            category.put("brandName", textValue(node, "brandname", "brandName"));
            categories.add(category);
        }
        return categories;
    }

    private RemoteGoodsItem kakayunRemoteGoodsItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    ) {
        String supplierGoodsId = textValue(node, "goodsid", "goodsId", "id");
        String categoryId = firstText(textValue(node, "groupid", "groupId"), selectedCategoryId, "");
        String categoryName = firstText(
            textValue(node, "groupname", "groupName", "groupaliasname", "groupAliasName"),
            categoryNames.get(categoryId),
            selectedCategoryName
        );
        int goodsStatus = intValue(firstExisting(node, "goodsstatus", "goodsStatus", "status"), 1);
        int goodsType = intValue(firstExisting(node, "goodstype", "goodsType", "type"), -1);
        GoodsChannelItem channel = sourceConnectedChannel(supplierId, supplierGoodsId).orElse(null);
        GoodsItem localGoods = channel == null ? null : findGoodsSnapshot(channel.goodsId()).orElse(null);
        return new RemoteGoodsItem(
            supplierGoodsId,
            textValue(node, "goodsname", "goodsName", "name", "title"),
            goodsType == 0 ? "CARD" : "DIRECT",
            categoryId,
            categoryName,
            decimalValue(node, "goodsprice", "goodsPrice", "price"),
            decimalValue(node, "marketprice", "marketPrice", "faceValue", "face_value"),
            intValue(firstExisting(node, "stock", "stockNum", "stock_num"), 0),
            goodsStatus == 1 ? "ON_SALE" : "OFF_SALE",
            goodsStatus == 1,
            goodsStatus != 1,
            channel != null,
            channel == null ? null : channel.goodsId(),
            localGoods == null ? "" : localGoods.goodsName(),
            channel == null ? null : channel.id(),
            OBJECT_MAPPER.convertValue(node, MAP_TYPE)
        );
    }

    private void validateKakayunCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("kakayun baseUrl is required");
        }
        if (!StringUtils.hasText(kakayunIdentity(item))) {
            throw new IllegalArgumentException("kakayun userid is required");
        }
        if (!StringUtils.hasText(kakayunApiKey(item))) {
            throw new IllegalArgumentException("kakayun key is required");
        }
    }

    private JsonNode kakayunPostJson(SupplierItem item, String path, Map<String, Object> bodyObject, String action) {
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        Map<String, Object> body = new LinkedHashMap<>();
        if (bodyObject != null) {
            body.putAll(bodyObject);
        }
        body.put("userid", kakayunIdentity(item));
        body.put("timestamp", Instant.now().getEpochSecond());
        body.put("sign", KakayunSignatureUtil.sign(body, kakayunApiKey(item)));
        HttpRequest request = HttpRequest.newBuilder(kasushouUri(item.baseUrl(), path))
            .timeout(timeout)
            .version(HttpClient.Version.HTTP_1_1)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "xiyiyun-kakayun-client/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(KakayunSignatureUtil.jsonBody(body), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("kakayun " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kakayun " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("kakayun " + action + " failed: invalid JSON response");
        } catch (Exception ex) {
            throw new IllegalStateException("kakayun " + action + " failed: " + ex.getMessage());
        }
    }

    private String kakayunApiKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private String kakayunIdentity(SupplierItem item) {
        return firstText(item.userId(), item.appId(), item.appKey());
    }

    private void ensureKakayunOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 1) {
            String message = textValue(root, "msg", "message", "error");
            throw new IllegalStateException("kakayun " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private BigDecimal kakayunBalance(JsonNode root) {
        BigDecimal balance = optionalDecimalValue(root, "money", "balance", "user_money", "account_balance");
        if (balance != null) {
            return balance;
        }
        JsonNode data = root.path("data");
        balance = optionalDecimalValue(data, "money", "balance", "user_money", "account_balance");
        if (balance != null) {
            return balance;
        }
        throw new IllegalStateException("kakayun balance refresh failed: balance field is missing");
    }

    private SupplierItem testFuluConnection(SupplierItem item) {
        validateFuluCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能测试真实连接");
        }
        JsonNode root = fuluPostJson(item, "merchant.balance.query", Map.of(), "test connection");
        ensureFuluOk(root, "test connection");
        verifyFuluResponseSign(root, item, "test connection");
        return item.withBalance(fuluBalance(root)).withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshFuluBalance(SupplierItem item) {
        validateFuluCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        JsonNode root = fuluPostJson(item, "merchant.balance.query", Map.of(), "balance refresh");
        ensureFuluOk(root, "balance refresh");
        verifyFuluResponseSign(root, item, "balance refresh");
        return item.withBalance(fuluBalance(root));
    }

    private SupplierItem testFengzhushouConnection(SupplierItem item) {
        validateFengzhushouCredentials(item);
        return item.withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshFengzhushouBalance(SupplierItem item) {
        validateFengzhushouCredentials(item);
        return item.withBalance(item.balance() == null ? BigDecimal.ZERO : item.balance()).withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem testChengquanConnection(SupplierItem item) {
        return refreshChengquanBalance(item).withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshChengquanBalance(SupplierItem item) {
        validateChengquanCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        Map<String, Object> body = chengquanBaseParams(item);
        body.put("sign", ChengquanSignatureUtil.sign(body, chengquanSecret(item)));
        JsonNode root = chengquanPostJson(item, "/user/balance/get", body, "balance refresh");
        ensureChengquanOk(root, "balance refresh");
        BigDecimal balance = optionalDecimalValue(root.path("data"), "balance", "money", "amount");
        if (balance == null) {
            balance = optionalDecimalValue(root, "balance", "money", "amount");
        }
        if (balance == null) {
            throw new IllegalStateException("chengquan balance refresh failed: balance field is missing");
        }
        return item.withBalance(balance).withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem testFanchenConnection(SupplierItem item) {
        return refreshFanchenBalance(item).withLastSyncAt(OffsetDateTime.now());
    }

    private SupplierItem refreshFanchenBalance(SupplierItem item) {
        validateFanchenCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能刷新真实余额");
        }
        Map<String, Object> body = fanchenBaseParams(item);
        body.put("sign", FanchenSignatureUtil.sign(body, List.of("userid"), fanchenKey(item)));
        JsonNode root = fanchenPostJson(item, "/fcsearchbalance.do", body, "balance refresh");
        ensureFanchenOk(root, "balance refresh", Set.of("1"));
        BigDecimal balance = optionalDecimalValue(root, "balance", "fundbalance", "fundBalance");
        if (balance == null) {
            throw new IllegalStateException("fanchen balance refresh failed: balance field is missing");
        }
        return item.withBalance(balance).withLastSyncAt(OffsetDateTime.now());
    }

    private void validateFuluCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fulu baseUrl is required");
        }
        if (!StringUtils.hasText(fuluAppKey(item))) {
            throw new IllegalArgumentException("fulu app_key is required");
        }
        if (!StringUtils.hasText(fuluAppSecret(item))) {
            throw new IllegalArgumentException("fulu app_secret is required");
        }
    }

    private void validateFengzhushouCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fengzhushou baseUrl is required");
        }
        if (!StringUtils.hasText(fengzhushouProjectCode(item))) {
            throw new IllegalArgumentException("fengzhushou projectCode is required");
        }
        if (!StringUtils.hasText(fengzhushouSignKey(item))) {
            throw new IllegalArgumentException("fengzhushou signKey is required");
        }
    }

    private JsonNode fengzhushouPostJson(SupplierItem item, String path, Map<String, Object> body, String action) {
        validateFengzhushouCredentials(item);
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        try {
            URI uri = URI.create(trimTrailingSlash(item.baseUrl().trim()) + path);
            String payload = OBJECT_MAPPER.writeValueAsString(body == null ? Map.of() : body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "xiyiyun-fengzhushou-client/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("fengzhushou " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fengzhushou " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fengzhushou " + action + " failed: invalid JSON payload or response");
        } catch (Exception ex) {
            throw new IllegalStateException("fengzhushou " + action + " failed: " + ex.getMessage());
        }
    }

    private void validateChengquanCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("chengquan baseUrl is required");
        }
        if (!StringUtils.hasText(chengquanAppId(item))) {
            throw new IllegalArgumentException("chengquan app_id is required");
        }
        if (!StringUtils.hasText(chengquanSecret(item))) {
            throw new IllegalArgumentException("chengquan key is required");
        }
    }

    private Map<String, Object> chengquanBaseParams(SupplierItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", chengquanAppId(item));
        body.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
        return body;
    }

    private JsonNode chengquanPostJson(SupplierItem item, String path, Map<String, Object> body, String action) {
        validateChengquanCredentials(item);
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        try {
            URI uri = URI.create(trimTrailingSlash(item.baseUrl().trim()) + path);
            String payload = OBJECT_MAPPER.writeValueAsString(body == null ? Map.of() : body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "xiyiyun-chengquan-client/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("chengquan " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chengquan " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("chengquan " + action + " failed: invalid JSON payload or response");
        } catch (Exception ex) {
            throw new IllegalStateException("chengquan " + action + " failed: " + ex.getMessage());
        }
    }

    private void ensureChengquanOk(JsonNode root, String action) {
        int code = intValue(firstExisting(root, "code", "retcode"), -1);
        if (code != 7000 && code != 0) {
            String message = textValue(root, "msg", "message", "error");
            throw new IllegalStateException("chengquan " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private SupplierItem testJingzhaoConnection(SupplierItem item) {
        return refreshJingzhaoBalance(item);
    }

    private SupplierItem refreshJingzhaoBalance(SupplierItem item) {
        validateJingzhaoCredentials(item);
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            return item.withBalance(item.balance() == null ? BigDecimal.ZERO : item.balance());
        }
        Map<String, Object> body = jingzhaoBaseParams(item);
        body.put("sign", JingzhaoSignatureUtil.sign(body, jingzhaoKey(item)));
        JsonNode root = jingzhaoPostJson(item, "/api/customer", body, "balance refresh");
        ensureJingzhaoOk(root, "balance refresh");
        return item.withBalance(decimalValue(root.path("data"), "balance"));
    }

    private void validateJingzhaoCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("jingzhao baseUrl is required");
        }
        if (!StringUtils.hasText(jingzhaoCustomerId(item))) {
            throw new IllegalArgumentException("jingzhao customer_id is required");
        }
        if (!StringUtils.hasText(jingzhaoKey(item))) {
            throw new IllegalArgumentException("jingzhao key is required");
        }
    }

    private Map<String, Object> jingzhaoBaseParams(SupplierItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_id", jingzhaoCustomerId(item));
        body.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        return body;
    }

    private JsonNode jingzhaoPostJson(SupplierItem item, String path, Map<String, Object> body, String action) {
        validateJingzhaoCredentials(item);
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        try {
            URI uri = URI.create(trimTrailingSlash(item.baseUrl().trim()) + path);
            String payload = JingzhaoSignatureUtil.formBody(body == null ? Map.of() : body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .header("User-Agent", "xiyiyun-jingzhao-client/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("jingzhao " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("jingzhao " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("jingzhao " + action + " failed: invalid JSON payload or response");
        } catch (Exception ex) {
            throw new IllegalStateException("jingzhao " + action + " failed: " + ex.getMessage());
        }
    }

    private void ensureJingzhaoOk(JsonNode root, String action) {
        String code = textValue(root, "code");
        if (!"ok".equalsIgnoreCase(defaultText(code, ""))) {
            String message = textValue(root, "message", "msg", "error");
            throw new IllegalStateException("jingzhao " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private void validateFanchenCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("fanchen baseUrl is required");
        }
        if (!StringUtils.hasText(fanchenUserId(item))) {
            throw new IllegalArgumentException("fanchen userid is required");
        }
        if (!StringUtils.hasText(fanchenKey(item))) {
            throw new IllegalArgumentException("fanchen key is required");
        }
    }

    private Map<String, Object> fanchenBaseParams(SupplierItem item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", fanchenUserId(item));
        return body;
    }

    private JsonNode fanchenPostJson(SupplierItem item, String path, Map<String, Object> body, String action) {
        validateFanchenCredentials(item);
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        try {
            URI uri = URI.create(trimTrailingSlash(item.baseUrl().trim()) + path);
            String payload = formUrlEncoded(body == null ? Map.of() : body, GBK_CHARSET);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=GBK")
                .header("Accept", "application/json")
                .header("User-Agent", "xiyiyun-fanchen-client/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, GBK_CHARSET))
                .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(GBK_CHARSET));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("fanchen " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fanchen " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fanchen " + action + " failed: invalid JSON response");
        } catch (Exception ex) {
            throw new IllegalStateException("fanchen " + action + " failed: " + ex.getMessage());
        }
    }

    private void ensureFanchenOk(JsonNode root, String action, Set<String> successCodes) {
        String code = textValue(root, "resultno");
        if (!successCodes.contains(code)) {
            String message = textValue(root, "remark1", "msg", "message");
            throw new IllegalStateException("fanchen " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private JsonNode fuluPostJson(SupplierItem item, String method, Map<String, Object> bizObject, String action) {
        validateFuluCredentials(item);
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        Map<String, Object> biz = new LinkedHashMap<>();
        if (bizObject != null) {
            biz.putAll(bizObject);
        }
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("app_key", fuluAppKey(item));
            body.put("method", method);
            body.put("timestamp", FULU_TIMESTAMP_FORMAT.format(ZonedDateTime.now(CHINA_ZONE)));
            body.put("version", "1.0");
            body.put("format", "json");
            body.put("charset", "utf-8");
            body.put("sign_type", "md5");
            body.put("biz_content", OBJECT_MAPPER.writeValueAsString(biz));
            body.put("sign", FuluSignatureUtil.requestSign(body, fuluAppSecret(item)));
            String payload = OBJECT_MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(item.baseUrl().trim()))
                .timeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "xiyiyun-fulu-client/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("fulu " + action + " failed: HTTP "
                    + response.statusCode() + " " + abbreviate(response.body(), 300));
            }
            return OBJECT_MAPPER.readTree(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fulu " + action + " interrupted");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fulu " + action + " failed: invalid JSON payload or response");
        } catch (Exception ex) {
            throw new IllegalStateException("fulu " + action + " failed: " + ex.getMessage());
        }
    }

    private void ensureFuluOk(JsonNode root, String action) {
        int code = intValue(root.path("code"), -1);
        if (code != 200) {
            String message = textValue(root, "msg", "message", "sub_msg", "error");
            throw new IllegalStateException("fulu " + action + " failed: code=" + code
                + (StringUtils.hasText(message) ? " message=" + message : ""));
        }
    }

    private void verifyFuluResponseSign(JsonNode root, SupplierItem item, String action) {
        String sign = textValue(root, "sign");
        String result = root.path("result").asText("");
        if (!StringUtils.hasText(sign) || !StringUtils.hasText(result)) {
            return;
        }
        String expected = FuluSignatureUtil.responseSign(result, fuluAppSecret(item));
        if (!Objects.equals(sign.trim().toLowerCase(Locale.ROOT), expected)) {
            throw new IllegalStateException("fulu " + action + " failed: invalid response sign");
        }
    }

    private SupplierItem resolveFuluCallbackSupplier(Long supplierId, Map<String, Object> body) {
        if (supplierId != null) {
            SupplierItem supplier = requiredSupplier(supplierId);
            if (!isFuluSupplier(supplier)) {
                throw new IllegalArgumentException("supplier is not 福禄新平台");
            }
            return supplier;
        }
        String appKey = callbackText(body.get("app_key"));
        return suppliers.values().stream()
            .filter(this::isFuluSupplier)
            .filter(item -> Objects.equals(fuluAppKey(item), appKey))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("fulu callback supplier not found"));
    }

    private void verifyFuluCallbackSign(Map<String, Object> body, SupplierItem supplier) {
        String sign = callbackText(body.get("sign"));
        if (!StringUtils.hasText(sign)) {
            throw new IllegalArgumentException("fulu callback sign is required");
        }
        Map<String, String> signParams = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (!"sign".equals(key) && value != null) {
                signParams.put(key, callbackText(value));
            }
        });
        String expected = FuluSignatureUtil.requestSign(signParams, fuluAppSecret(supplier));
        if (!Objects.equals(sign.trim().toLowerCase(Locale.ROOT), expected)) {
            throw new IllegalArgumentException("fulu callback sign invalid");
        }
    }

    private String callbackText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private BigDecimal fuluBalance(JsonNode root) {
        String result = root.path("result").asText("");
        if (!StringUtils.hasText(result)) {
            throw new IllegalStateException("fulu balance refresh failed: result is empty");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(result);
            JsonNode balances = firstExisting(node, "Balances", "balances", "BalanceList", "balanceList");
            BigDecimal fallback = optionalDecimalValue(node, "Balance", "balance", "amount");
            if (balances != null && balances.isArray()) {
                for (JsonNode item : balances) {
                    int accountType = intValue(firstExisting(item, "AccountType", "accountType"), -1);
                    BigDecimal balance = optionalDecimalValue(item, "Balance", "balance");
                    if (accountType == 1 && balance != null) {
                        return balance;
                    }
                    if (fallback == null && balance != null) {
                        fallback = balance;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fulu balance refresh failed: invalid result JSON");
        }
        throw new IllegalStateException("fulu balance refresh failed: balance field is missing");
    }

    private FuluOrderStatus fuluOrderStatusFromResult(String result) {
        if (!StringUtils.hasText(result)) {
            throw new IllegalStateException("fulu order info sync failed: result is empty");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(result);
            List<String> cards = new ArrayList<>();
            JsonNode cardList = firstExisting(node, "card_pwds", "cardPwds", "cards", "card_list");
            if (cardList != null && cardList.isArray()) {
                for (JsonNode card : cardList) {
                    String cardNo = textValue(card, "card_no", "cardNo", "card");
                    String cardPwd = textValue(card, "card_pwd", "cardPwd", "card_password", "password");
                    String expireTime = textValue(card, "expire_time", "expireTime");
                    String line = StringUtils.hasText(cardNo)
                        ? "卡号：" + cardNo + (StringUtils.hasText(cardPwd) ? " 卡密：" + cardPwd : "")
                        : (StringUtils.hasText(cardPwd) ? "卡密：" + cardPwd : "");
                    if (StringUtils.hasText(line) && StringUtils.hasText(expireTime)) {
                        line = line + " 有效期：" + expireTime;
                    }
                    if (StringUtils.hasText(line)) {
                        cards.add(line);
                    }
                }
            }
            List<String> hints = new ArrayList<>();
            for (String value : List.of(
                textValue(node, "charge_remark", "chargeRemark", "message", "msg"),
                textValue(node, "inner_charge_remark", "innerChargeRemark"),
                textValue(node, "express_name", "expressName"),
                textValue(node, "express_no", "expressNo")
            )) {
                if (StringUtils.hasText(value)) {
                    hints.add(value);
                }
            }
            return new FuluOrderStatus(
                textValue(node, "order_id", "orderId", "order_no", "orderNo"),
                textValue(node, "customer_order_no", "customerOrderNo"),
                textValue(node, "product_id", "productId"),
                textValue(node, "product_name", "productName"),
                intValue(firstExisting(node, "order_status", "orderStatus", "status"), 0),
                String.join("；", hints),
                optionalDecimalValue(node, "total_price", "totalPrice", "customer_price", "customerPrice"),
                List.copyOf(cards),
                abbreviate(node.toString(), 1200)
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("fulu order info sync failed: invalid result JSON");
        }
    }

    private String fuluAppKey(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private String fuluAppSecret(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private String fengzhushouProjectCode(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private String fengzhushouSignKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private String chengquanAppId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private String chengquanSecret(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private String fanchenUserId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private String fanchenKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private String jingzhaoCustomerId(SupplierItem item) {
        return firstText(item.appId(), item.userId(), item.appKey());
    }

    private String jingzhaoKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
    }

    private SupplierItem resolveFengzhushouCallbackSupplier(Long supplierId, Map<String, Object> body) {
        if (supplierId != null) {
            SupplierItem supplier = requiredSupplier(supplierId);
            if (!isFengzhushouSupplier(supplier)) {
                throw new IllegalArgumentException("supplier is not 蜂助手");
            }
            return supplier;
        }
        String projectCode = callbackText(body.get("projectCode"));
        return suppliers.values().stream()
            .filter(this::isFengzhushouSupplier)
            .filter(item -> Objects.equals(fengzhushouProjectCode(item), projectCode))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("fengzhushou callback supplier not found"));
    }

    private void verifyFengzhushouCallbackSign(Map<String, Object> body, SupplierItem supplier) {
        String sign = callbackText(body.get("sign"));
        if (!StringUtils.hasText(sign)) {
            throw new IllegalArgumentException("fengzhushou callback sign is required");
        }
        String expected = FengzhushouSignatureUtil.sign(body, fengzhushouSignKey(supplier));
        if (!Objects.equals(sign.trim().toUpperCase(Locale.ROOT), expected)) {
            throw new IllegalArgumentException("fengzhushou callback sign invalid");
        }
    }

    private SupplierItem resolveChengquanCallbackSupplier(Long supplierId, Map<String, Object> body) {
        if (supplierId != null) {
            SupplierItem supplier = requiredSupplier(supplierId);
            if (!isChengquanSupplier(supplier)) {
                throw new IllegalArgumentException("supplier is not 鼎信橙券");
            }
            return supplier;
        }
        String appId = callbackText(body.get("app_id"));
        return suppliers.values().stream()
            .filter(this::isChengquanSupplier)
            .filter(item -> Objects.equals(chengquanAppId(item), appId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("chengquan callback supplier not found"));
    }

    private void verifyChengquanCallbackSign(Map<String, Object> body, SupplierItem supplier) {
        String sign = callbackText(body.get("sign"));
        if (!StringUtils.hasText(sign)) {
            throw new IllegalArgumentException("chengquan callback sign is required");
        }
        String expected = ChengquanSignatureUtil.sign(body, chengquanSecret(supplier));
        if (!Objects.equals(sign.trim().toUpperCase(Locale.ROOT), expected)) {
            throw new IllegalArgumentException("chengquan callback sign invalid");
        }
    }

    private SupplierItem resolveFanchenCallbackSupplier(Long supplierId, Map<String, Object> body) {
        if (supplierId != null) {
            SupplierItem supplier = requiredSupplier(supplierId);
            if (!isFanchenSupplier(supplier)) {
                throw new IllegalArgumentException("supplier is not 浙江梵尘");
            }
            return supplier;
        }
        String userId = callbackText(body.get("userid"));
        return suppliers.values().stream()
            .filter(this::isFanchenSupplier)
            .filter(item -> Objects.equals(fanchenUserId(item), userId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("fanchen callback supplier not found"));
    }

    private void verifyFanchenCallbackSign(Map<String, Object> body, SupplierItem supplier) {
        String sign = callbackText(body.get("sign"));
        if (!StringUtils.hasText(sign)) {
            throw new IllegalArgumentException("fanchen callback sign is required");
        }
        String expected = FanchenSignatureUtil.sign(
            body,
            List.of("userid", "orderid", "sporderid", "merchantsubmittime", "resultno"),
            fanchenKey(supplier)
        );
        if (!Objects.equals(sign.trim().toUpperCase(Locale.ROOT), expected)) {
            throw new IllegalArgumentException("fanchen callback sign invalid");
        }
    }

    private SupplierItem resolveJingzhaoCallbackSupplier(Long supplierId, Map<String, Object> body) {
        if (supplierId != null) {
            SupplierItem supplier = requiredSupplier(supplierId);
            if (!isJingzhaoSupplier(supplier)) {
                throw new IllegalArgumentException("supplier is not 京兆云");
            }
            return supplier;
        }
        String customerId = callbackText(body.get("customer_id"));
        return suppliers.values().stream()
            .filter(this::isJingzhaoSupplier)
            .filter(item -> Objects.equals(jingzhaoCustomerId(item), customerId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("jingzhao callback supplier not found"));
    }

    private void verifyJingzhaoCallbackSign(Map<String, Object> body, SupplierItem supplier) {
        String sign = callbackText(body.get("sign"));
        if (!StringUtils.hasText(sign)) {
            throw new IllegalArgumentException("jingzhao callback sign is required");
        }
        String expected = JingzhaoSignatureUtil.sign(body, jingzhaoKey(supplier));
        if (!Objects.equals(sign.trim().toLowerCase(Locale.ROOT), expected)) {
            throw new IllegalArgumentException("jingzhao callback sign invalid");
        }
    }

    private String trimTrailingSlash(String value) {
        String trimmed = defaultText(value, "").trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String formUrlEncoded(Map<String, Object> body, Charset charset) {
        return body.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), charset) + "=" + URLEncoder.encode(defaultText(entry.getValue(), ""), charset))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private void validateKasushouCredentials(SupplierItem item) {
        if (!StringUtils.hasText(item.baseUrl())) {
            throw new IllegalArgumentException("kasushou baseUrl is required");
        }
        if (!StringUtils.hasText(kasushouIdentity(item))) {
            throw new IllegalArgumentException("kasushou appId is required");
        }
        String apiKey = kasushouApiKey(item);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("kasushou apiKey is required");
        }
    }

    private JsonNode kasushouPostJson(SupplierItem item, String path, Object bodyObject, String action) {
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(item.timeoutSeconds()));
        String apiKey = kasushouApiKey(item);
        String body = KasushouSignatureUtil.sortedJsonBody(bodyObject);
        return kasushouPostJson(item, path, action, timeout, apiKey, body);
    }

    private JsonNode kasushouPostJson(
        SupplierItem item,
        String path,
        String action,
        Duration timeout,
        String apiKey,
        String body
    ) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String userId = kasushouIdentity(item);
        String sign = KasushouSignatureUtil.signRaw(timestamp, body, apiKey);
        HttpRequest request = HttpRequest.newBuilder(kasushouUri(item.baseUrl(), path))
            .timeout(timeout)
            .version(HttpClient.Version.HTTP_1_1)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "xiyiyun-kasushou-client/1.0")
            .header("Sign", sign)
            .header("Timestamp", timestamp)
            .header("UserId", userId)
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

    private String kasushouApiKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
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

    private RemoteGoodsItem remoteGoodsItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    ) {
        String supplierGoodsId = textValue(node, "id", "goods_id", "goodsId");
        String categoryId = firstText(
            textValue(node, "cate_id", "cateId", "category_id", "categoryId"),
            selectedCategoryId,
            ""
        );
        String categoryName = firstText(
            textValue(node, "cate_name", "cateName", "category_name", "categoryName"),
            categoryNames.get(categoryId),
            selectedCategoryName
        );
        GoodsChannelItem channel = sourceConnectedChannel(supplierId, supplierGoodsId).orElse(null);
        GoodsItem localGoods = channel == null ? null : findGoodsSnapshot(channel.goodsId()).orElse(null);
        return new RemoteGoodsItem(
            supplierGoodsId,
            textValue(node, "goods_name", "goodsName", "name", "title"),
            textValue(node, "goods_type", "goodsType", "type"),
            categoryId,
            categoryName,
            decimalValue(node, "goods_price", "goodsPrice", "price"),
            decimalValue(node, "face_value", "faceValue", "face"),
            intValue(firstExisting(node, "stock_num", "stockNum", "stock", "num"), 0),
            textValue(node, "status", "state"),
            booleanValue(node, "can_buy", "canBuy"),
            booleanValue(node, "can_no_buy", "canNoBuy", "can_not_buy"),
            channel != null,
            channel == null ? null : channel.goodsId(),
            localGoods == null ? "" : localGoods.goodsName(),
            channel == null ? null : channel.id(),
            OBJECT_MAPPER.convertValue(node, MAP_TYPE)
        );
    }

    private Map<String, String> remoteCategoryNames(List<Map<String, Object>> categories) {
        Map<String, String> names = new java.util.HashMap<>();
        appendRemoteCategoryNames(categories, names);
        return names;
    }

    private void appendRemoteCategoryNames(List<Map<String, Object>> categories, Map<String, String> names) {
        if (categories == null) {
            return;
        }
        for (Map<String, Object> category : categories) {
            if (category == null) {
                continue;
            }
            Object rawId = category.get("id");
            Object rawName = category.get("name");
            String id = rawId == null ? "" : String.valueOf(rawId);
            String name = rawName == null ? "" : String.valueOf(rawName);
            if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
                names.put(id, name);
            }
            Object children = category.get("children");
            if (children instanceof List<?> childList) {
                List<Map<String, Object>> childCategories = childList.stream()
                    .filter(Map.class::isInstance)
                    .map(child -> (Map<String, Object>) child)
                    .toList();
                appendRemoteCategoryNames(childCategories, names);
            }
        }
    }

    private List<RemoteGoodsItem> enrichKasushouCategoryInfo(
        SupplierItem item,
        List<Map<String, Object>> categories,
        Map<String, String> categoryNames,
        String keyword,
        List<RemoteGoodsItem> items
    ) {
        Set<String> missingIds = items.stream()
            .filter(goods -> !StringUtils.hasText(goods.categoryName()))
            .map(RemoteGoodsItem::supplierGoodsId)
            .filter(StringUtils::hasText)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (missingIds.isEmpty()) {
            return items;
        }

        Map<String, RemoteCategoryRef> matchedCategories = new java.util.HashMap<>();
        int requests = 0;
        for (RemoteCategoryRef category : prioritizedRemoteCategoryRefs(categories, keyword)) {
            if (missingIds.isEmpty() || requests >= KASUSHOU_CATEGORY_ENRICH_MAX_REQUESTS) {
                break;
            }
            int page = 1;
            int total = Integer.MAX_VALUE;
            while (missingIds.size() > 0
                && page <= KASUSHOU_CATEGORY_ENRICH_MAX_PAGES
                && (page - 1) * KASUSHOU_CATEGORY_ENRICH_LIMIT < total
                && requests < KASUSHOU_CATEGORY_ENRICH_MAX_REQUESTS) {
                requests++;
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("cate_id", category.id());
                body.put("keyword", defaultText(keyword, ""));
                body.put("limit", KASUSHOU_CATEGORY_ENRICH_LIMIT);
                body.put("page", page);
                JsonNode root = kasushouPostJson(item, "/api/v1/goods/list", body, "category goods lookup");
                ensureKasushouOk(root, "category goods lookup");
                JsonNode data = root.path("data");
                total = intValue(data.path("total"), 0);
                JsonNode listNode = data.path("list");
                if (!listNode.isArray()) {
                    break;
                }
                for (JsonNode node : listNode) {
                    String remoteId = textValue(node, "id", "goods_id", "goodsId");
                    if (missingIds.remove(remoteId)) {
                        matchedCategories.put(remoteId, category);
                    }
                }
                page++;
            }
        }

        if (matchedCategories.isEmpty()) {
            return items;
        }
        return items.stream()
            .map(goods -> {
                RemoteCategoryRef category = matchedCategories.get(goods.supplierGoodsId());
                if (category == null) {
                    return goods;
                }
                return remoteGoodsItemWithCategory(goods, category.id(), categoryNames.getOrDefault(category.id(), category.name()));
            })
            .toList();
    }

    private List<RemoteCategoryRef> prioritizedRemoteCategoryRefs(List<Map<String, Object>> categories, String keyword) {
        String normalizedKeyword = normalize(keyword);
        List<RemoteCategoryRef> refs = new ArrayList<>();
        appendRemoteCategoryRefs(categories, refs);
        return refs.stream()
            .sorted(Comparator
                .comparing((RemoteCategoryRef ref) -> !StringUtils.hasText(normalizedKeyword) || !normalize(ref.name()).contains(normalizedKeyword))
                .thenComparing(RemoteCategoryRef::depth)
                .thenComparing(RemoteCategoryRef::name))
            .toList();
    }

    private void appendRemoteCategoryRefs(List<Map<String, Object>> categories, List<RemoteCategoryRef> refs) {
        appendRemoteCategoryRefs(categories, refs, 0);
    }

    private void appendRemoteCategoryRefs(List<Map<String, Object>> categories, List<RemoteCategoryRef> refs, int depth) {
        if (categories == null) {
            return;
        }
        for (Map<String, Object> category : categories) {
            if (category == null) {
                continue;
            }
            Object children = category.get("children");
            if (children instanceof List<?> childList) {
                List<Map<String, Object>> childCategories = childList.stream()
                    .filter(Map.class::isInstance)
                    .map(child -> (Map<String, Object>) child)
                    .toList();
                appendRemoteCategoryRefs(childCategories, refs, depth + 1);
            }
            Object rawId = category.get("id");
            Object rawName = category.get("name");
            String id = rawId == null ? "" : String.valueOf(rawId);
            String name = rawName == null ? "" : String.valueOf(rawName);
            if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
                refs.add(new RemoteCategoryRef(id, name, depth));
            }
        }
    }

    private RemoteGoodsItem remoteGoodsItemWithCategory(RemoteGoodsItem item, String categoryId, String categoryName) {
        return new RemoteGoodsItem(
            item.supplierGoodsId(),
            item.goodsName(),
            item.goodsType(),
            categoryId,
            categoryName,
            item.goodsPrice(),
            item.faceValue(),
            item.stockNum(),
            item.status(),
            item.canBuy(),
            item.canNoBuy(),
            item.connected(),
            item.localGoodsId(),
            item.localGoodsName(),
            item.channelId(),
            item.raw()
        );
    }

    private Optional<GoodsChannelItem> sourceConnectedChannel(Long supplierId, String supplierGoodsId) {
        if (supplierId == null || !StringUtils.hasText(supplierGoodsId)) {
            return Optional.empty();
        }
        String normalizedId = supplierGoodsId.trim();
        return allGoodsChannelSnapshots().stream()
            .filter(channel -> Objects.equals(channel.supplierId(), supplierId))
            .filter(channel -> Objects.equals(defaultText(channel.supplierGoodsId(), "").trim(), normalizedId))
            .min(Comparator.comparing(GoodsChannelItem::id));
    }

    private record RemoteCategoryRef(String id, String name, int depth) {
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

    private BigDecimal decimalValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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

    private boolean isKakayunSupplier(SupplierItem item) {
        return item != null && isKakayunPlatform(item.platformType());
    }

    private boolean isFuluSupplier(SupplierItem item) {
        return item != null && isFuluPlatform(item.platformType());
    }

    private boolean isFengzhushouSupplier(SupplierItem item) {
        return item != null && isFengzhushouPlatform(item.platformType());
    }

    private boolean isChengquanSupplier(SupplierItem item) {
        return item != null && isChengquanPlatform(item.platformType());
    }

    private boolean isFanchenSupplier(SupplierItem item) {
        return item != null && isFanchenPlatform(item.platformType());
    }

    private boolean isJingzhaoSupplier(SupplierItem item) {
        return item != null && isJingzhaoPlatform(item.platformType());
    }

    private boolean isApiSupplier(SupplierItem item) {
        return item != null && isApiSupplierPlatform(item.platformType());
    }

    private boolean supportsRemoteGoodsSync(SupplierItem item) {
        return isApiSupplier(item) || isChengquanSupplier(item) || isFanchenSupplier(item) || isJingzhaoSupplier(item);
    }

    private boolean isApiSupplierPlatform(String platformType) {
        return isKasushouPlatform(platformType) || isKakayunPlatform(platformType);
    }

    private boolean isKasushouPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        return "kasushou_2".equals(normalizedPlatformType)
            || "kasushou".equals(normalizedPlatformType)
            || "kasu".equals(normalizedPlatformType);
    }

    private boolean isKakayunPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        return "kakayun".equals(normalizedPlatformType)
            || "kaka_yun".equals(normalizedPlatformType)
            || "kky".equals(normalizedPlatformType)
            || "卡卡云".equals(defaultText(platformType, "").trim());
    }

    private boolean isFuluPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        String raw = defaultText(platformType, "").trim();
        return "fulu".equals(normalizedPlatformType)
            || "fulu_new".equals(normalizedPlatformType)
            || "fulu_new_platform".equals(normalizedPlatformType)
            || "福禄".equals(raw)
            || "福禄新平台".equals(raw);
    }

    private boolean isFengzhushouPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        String raw = defaultText(platformType, "").trim();
        return "fengzhushou".equals(normalizedPlatformType)
            || "feng_zhushou".equals(normalizedPlatformType)
            || "fzs".equals(normalizedPlatformType)
            || "phone580".equals(normalizedPlatformType)
            || "蜂助手".equals(raw)
            || "蜂助手直充".equals(raw);
    }

    private boolean isChengquanPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        String raw = defaultText(platformType, "").trim();
        return "chengquan".equals(normalizedPlatformType)
            || "dx_chengquan".equals(normalizedPlatformType)
            || "dingxin_chengquan".equals(normalizedPlatformType)
            || "橙券".equals(raw)
            || "鼎信橙券".equals(raw);
    }

    private boolean isFanchenPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        String raw = defaultText(platformType, "").trim();
        return "fanchen_rj".equals(normalizedPlatformType)
            || "fanchen".equals(normalizedPlatformType)
            || "zhejiang_fanchen".equals(normalizedPlatformType)
            || "梵尘瑞景".equals(raw)
            || "浙江梵尘".equals(raw);
    }

    private boolean isJingzhaoPlatform(String platformType) {
        String normalizedPlatformType = normalize(platformType).replace("-", "_");
        String raw = defaultText(platformType, "").trim();
        return "jingzhao".equals(normalizedPlatformType)
            || "jingzhao_yun".equals(normalizedPlatformType)
            || "xhygo".equals(normalizedPlatformType)
            || "京兆".equals(raw)
            || "京兆云".equals(raw);
    }

    private String platformLabelForManualSupplier(SupplierItem item) {
        if (isFuluSupplier(item)) {
            return "福禄新平台";
        }
        if (isFengzhushouSupplier(item)) {
            return "蜂助手";
        }
        if (isChengquanSupplier(item)) {
            return "鼎信橙券";
        }
        if (isFanchenSupplier(item)) {
            return "浙江梵尘";
        }
        if (isJingzhaoSupplier(item)) {
            return "京兆云";
        }
        return "该平台";
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

    private String normalizeClientIp(String value) {
        String ip = defaultText(value, "").trim();
        if (!StringUtils.hasText(ip)) {
            return "";
        }
        int comma = ip.indexOf(',');
        if (comma >= 0) {
            ip = ip.substring(0, comma).trim();
        }
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) {
                return ip.substring(1, end);
            }
        }
        int colon = ip.indexOf(':');
        if (colon > 0 && ip.indexOf(':', colon + 1) < 0 && ip.substring(colon + 1).matches("\\d+")) {
            ip = ip.substring(0, colon);
        }
        return ip;
    }

    private String ipLocation(String ip) {
        String normalizedIp = normalizeClientIp(ip);
        if (!StringUtils.hasText(normalizedIp)) {
            return "";
        }
        String lower = normalizedIp.toLowerCase(Locale.ROOT);
        if ("127.0.0.1".equals(lower) || "::1".equals(lower) || "localhost".equals(lower)) {
            return "本机";
        }
        if (lower.startsWith("10.")
            || lower.startsWith("192.168.")
            || lower.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")
            || lower.startsWith("fc")
            || lower.startsWith("fd")
            || lower.startsWith("fe80:")) {
            return "内网";
        }
        return "公网 IP";
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
        String orderSource,
        String orderIp,
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
            orderSource,
            orderIp,
            ipLocation(orderIp),
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

    private String orderSource(CreateOrderRequest request, String defaultTerminal) {
        String fallbackTerminal = defaultText(defaultTerminal, "h5").trim().toLowerCase();
        if ("api".equals(fallbackTerminal)) {
            return "api";
        }
        String terminal = defaultText(request == null ? null : request.terminal(), fallbackTerminal).trim().toLowerCase();
        return switch (terminal) {
            case "web", "pc" -> "web";
            case "api", "member-api", "member_api" -> "api".equals(fallbackTerminal) ? "api" : "h5";
            default -> "h5";
        };
    }

    private void validateGoodsSalePlatform(GoodsItem item, String platform) {
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

    private String nextOrderNo(Long userId) {
        String normalizedUserId = userId == null ? "00000" : String.valueOf(userId);
        for (int i = 0; i < 20; i++) {
            String candidate = "xiyi"
                + OffsetDateTime.now().format(ORDER_NO_TIMESTAMP_FORMAT)
                + normalizedUserId
                + String.format("%04d", orderSeq.getAndIncrement());
            if (!orders.containsKey(candidate) && persistentOrder(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "xiyi"
            + OffsetDateTime.now().format(ORDER_NO_TIMESTAMP_FORMAT)
            + normalizedUserId
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

    private void deletePersistentGoods(Long id) {
        if (catalogPersistenceStore == null || id == null) {
            return;
        }
        try {
            catalogPersistenceStore.deleteGoods(id);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS", String.valueOf(id), persistenceErrorMessage(ex));
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

    private void deletePersistentGoodsChannelsByGoods(Long goodsId) {
        if (configPersistenceStore == null || goodsId == null) {
            return;
        }
        try {
            configPersistenceStore.deleteGoodsChannelsByGoods(goodsId);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "GOODS_CHANNEL", String.valueOf(goodsId), persistenceErrorMessage(ex));
        }
    }

    private void deletePersistentCardsByGoods(Long goodsId) {
        if (persistentOrderStore == null || goodsId == null) {
            return;
        }
        try {
            persistentOrderStore.deleteCardsByGoods(goodsId);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CARD", String.valueOf(goodsId), persistenceErrorMessage(ex));
        }
    }

    private boolean deletePersistentOrderData(String orderNo) {
        if (persistentOrderStore == null || !StringUtils.hasText(orderNo)) {
            return false;
        }
        try {
            return persistentOrderStore.deleteOrderData(orderNo);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ORDER", orderNo, persistenceErrorMessage(ex));
            throw ex;
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

    private void persistRuntimeSetting(String key, String value) {
        if (configPersistenceStore == null || !StringUtils.hasText(key)) {
            return;
        }
        try {
            configPersistenceStore.saveRuntimeSetting(key, value);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SETTING", key, persistenceErrorMessage(ex));
        }
    }

    private void persistPaymentChannels() {
        try {
            List<Map<String, Object>> payload = listPaymentChannels().stream()
                .map(this::paymentChannelPayload)
                .toList();
            persistRuntimeSetting(PAYMENT_CHANNEL_SETTING_KEY, OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PAYMENT_CHANNEL", "LIST", ex.getMessage());
        }
    }

    private void persistPriceTemplates() {
        try {
            persistRuntimeSetting(PRICE_TEMPLATE_SETTING_KEY, OBJECT_MAPPER.writeValueAsString(listPriceTemplates()));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PRICE_TEMPLATE", "LIST", ex.getMessage());
        }
    }

    private void persistMemberCredential(MemberApiCredentialItem item) {
        if (item == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", item.id());
            payload.put("userId", item.userId());
            payload.put("appKey", item.appKey());
            payload.put("appSecretMasked", mask(item.appSecret()));
            if (configPersistenceStore != null && StringUtils.hasText(item.appSecret())) {
                Map<String, String> encryptedSecret = configPersistenceStore.encryptSecretForSetting(item.appSecret());
                payload.put("appSecretCiphertext", encryptedSecret.get("ciphertext"));
                payload.put("appSecretNonce", encryptedSecret.get("nonce"));
                payload.put("appSecretKeyVersion", encryptedSecret.get("keyVersion"));
                payload.put("appSecretHash", encryptedSecret.get("hash"));
            }
            payload.put("status", item.status());
            payload.put("ipWhitelist", item.ipWhitelist());
            payload.put("dailyLimit", item.dailyLimit());
            payload.put("createdAt", item.createdAt() == null ? "" : item.createdAt().toString());
            payload.put("lastUsedAt", item.lastUsedAt() == null ? "" : item.lastUsedAt().toString());
            persistRuntimeSetting("member.credential." + item.userId(), OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "MEMBER_API", String.valueOf(item.userId()), ex.getMessage());
        }
    }

    private void persistSmsLoginSetting() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enabled", smsLoginSetting.enabled());
            payload.put("adminLoginEnabled", smsLoginSetting.adminLoginEnabled());
            payload.put("h5LoginEnabled", smsLoginSetting.h5LoginEnabled());
            payload.put("webLoginEnabled", smsLoginSetting.webLoginEnabled());
            payload.put("provider", smsLoginSetting.provider());
            payload.put("adminMobile", smsLoginSetting.adminMobile());
            payload.put("codeLength", smsLoginSetting.codeLength());
            payload.put("ttlSeconds", smsLoginSetting.ttlSeconds());
            payload.put("cooldownSeconds", smsLoginSetting.cooldownSeconds());
            payload.put("maxAttempts", smsLoginSetting.maxAttempts());
            payload.put("genericConfig", smsLoginSetting.genericConfig());
            payload.put("tencentConfig", smsLoginSetting.tencentConfig());
            payload.put("aliyunConfig", smsLoginSetting.aliyunConfig());
            persistRuntimeSetting(SMS_LOGIN_SETTING_KEY, OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SMS_LOGIN_SETTING", "GLOBAL", ex.getMessage());
        }
    }

    private void persistCaptchaSetting() {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enabled", captchaSetting.enabled());
            payload.put("adminLoginEnabled", captchaSetting.adminLoginEnabled());
            payload.put("h5LoginEnabled", captchaSetting.h5LoginEnabled());
            payload.put("webLoginEnabled", captchaSetting.webLoginEnabled());
            payload.put("provider", captchaSetting.provider());
            payload.put("tencentConfig", captchaSetting.tencentConfig());
            payload.put("turnstileConfig", captchaSetting.turnstileConfig());
            payload.put("genericConfig", captchaSetting.genericConfig());
            persistRuntimeSetting(CAPTCHA_SETTING_KEY, OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CAPTCHA_SETTING", "GLOBAL", ex.getMessage());
        }
    }

    private void persistAdminStaff() {
        try {
            List<Map<String, Object>> payload = listAdminStaff().stream()
                .map(item -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("id", item.id());
                    data.put("account", item.account());
                    data.put("nickname", item.nickname());
                    data.put("status", item.status());
                    data.put("permissions", item.permissions());
                    data.put("createdAt", item.createdAt() == null ? "" : item.createdAt().toString());
                    data.put("updatedAt", item.updatedAt() == null ? "" : item.updatedAt().toString());
                    data.put("passwordHash", adminStaffPasswordHashes.get(item.id()));
                    return data;
                })
                .toList();
            persistRuntimeSetting(ADMIN_STAFF_SETTING_KEY, OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ADMIN_STAFF", "LIST", ex.getMessage());
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

    private Optional<List<UserItem>> persistentUsers() {
        if (catalogPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<UserItem> items = catalogPersistenceStore.listUsers();
            return Optional.of(items);
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

    private Optional<List<SupplierItem>> persistentSuppliers() {
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

    private Optional<List<UserGroupItem>> persistentUserGroups() {
        if (configPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<UserGroupItem> items = configPersistenceStore.listUserGroups();
            return Optional.of(items);
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
            return Optional.of(items);
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

    private void loadSuperAdminCredentials() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            Map<String, String> settings = configPersistenceStore.systemSettings();
            String username = normalize(defaultText(settings.get(SUPER_ADMIN_USERNAME_KEY), ""));
            String passwordHash = defaultText(settings.get(SUPER_ADMIN_PASSWORD_KEY), "");
            String nickname = defaultText(settings.get(SUPER_ADMIN_NICKNAME_KEY), "").trim();
            if (StringUtils.hasText(username)) {
                adminUsername = username;
            }
            if (StringUtils.hasText(passwordHash)) {
                adminPasswordBcrypt = passwordHash;
            }
            if (StringUtils.hasText(nickname)) {
                adminNickname = nickname;
            }
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SUPER_ADMIN", "CREDENTIALS", persistenceErrorMessage(ex));
        }
    }

    private void loadSmsLoginSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configPersistenceStore.systemSettings().get(SMS_LOGIN_SETTING_KEY);
            if (!StringUtils.hasText(raw)) {
                return;
            }
            Map<String, Object> payload = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            smsLoginSetting = new SmsLoginSettingItem(
                booleanValue(payload.get("enabled"), smsLoginSetting.enabled()),
                booleanValue(payload.get("adminLoginEnabled"), smsLoginSetting.adminLoginEnabled()),
                booleanValue(payload.get("h5LoginEnabled"), smsLoginSetting.h5LoginEnabled()),
                booleanValue(payload.get("webLoginEnabled"), smsLoginSetting.webLoginEnabled()),
                normalizeSmsProvider(defaultText(payload.get("provider"), smsLoginSetting.provider())),
                defaultText(payload.get("adminMobile"), smsLoginSetting.adminMobile()),
                clampInt(intValue(payload.get("codeLength"), smsLoginSetting.codeLength()), 4, 8),
                clampInt(intValue(payload.get("ttlSeconds"), smsLoginSetting.ttlSeconds()), 60, 1800),
                clampInt(intValue(payload.get("cooldownSeconds"), smsLoginSetting.cooldownSeconds()), 10, 300),
                clampInt(intValue(payload.get("maxAttempts"), smsLoginSetting.maxAttempts()), 1, 10),
                normalizeSmsConfig(stringMap(payload.get("genericConfig"))),
                normalizeSmsConfig(stringMap(payload.get("tencentConfig"))),
                normalizeSmsConfig(stringMap(payload.get("aliyunConfig")))
            );
        } catch (RuntimeException | JsonProcessingException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SMS_LOGIN_SETTING", "GLOBAL", ex.getMessage());
        }
    }

    private void loadCaptchaSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configPersistenceStore.systemSettings().get(CAPTCHA_SETTING_KEY);
            if (!StringUtils.hasText(raw)) {
                return;
            }
            Map<String, Object> payload = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            captchaSetting = new CaptchaSettingItem(
                booleanValue(payload.get("enabled"), captchaSetting.enabled()),
                booleanValue(payload.get("adminLoginEnabled"), captchaSetting.adminLoginEnabled()),
                booleanValue(payload.get("h5LoginEnabled"), captchaSetting.h5LoginEnabled()),
                booleanValue(payload.get("webLoginEnabled"), captchaSetting.webLoginEnabled()),
                normalizeCaptchaProvider(defaultText(payload.get("provider"), captchaSetting.provider())),
                normalizeSmsConfig(stringMap(payload.get("tencentConfig"))),
                normalizeSmsConfig(stringMap(payload.get("turnstileConfig"))),
                normalizeSmsConfig(stringMap(payload.get("genericConfig")))
            );
        } catch (RuntimeException | JsonProcessingException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "CAPTCHA_SETTING", "GLOBAL", ex.getMessage());
        }
    }

    private void loadAdminStaff() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configPersistenceStore.systemSettings().get(ADMIN_STAFF_SETTING_KEY);
            if (!StringUtils.hasText(raw)) {
                return;
            }
            adminStaff.clear();
            adminStaffPasswordHashes.clear();
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(raw, LIST_MAP_TYPE);
            items.stream()
                .filter(Objects::nonNull)
                .forEach(item -> {
                    AdminStaffItem staff = adminStaffFromPayload(item);
                    String hash = defaultText(item.get("passwordHash"), "");
                    if (staff.id() != null && StringUtils.hasText(staff.account()) && StringUtils.hasText(hash)) {
                        adminStaff.put(staff.id(), staff);
                        adminStaffPasswordHashes.put(staff.id(), hash);
                    }
                });
            adminStaffId.set(maxAdminStaffId() + 1);
        } catch (RuntimeException | JsonProcessingException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "ADMIN_STAFF", "LIST", ex.getMessage());
        }
    }

    private void loadPaymentChannels() {
        if (configPersistenceStore == null) {
            seedPaymentChannels(false);
            return;
        }
        try {
            String raw = configPersistenceStore.systemSettings().get(PAYMENT_CHANNEL_SETTING_KEY);
            if (!StringUtils.hasText(raw)) {
                seedPaymentChannels(true);
                return;
            }
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(raw, LIST_MAP_TYPE);
            paymentChannels.clear();
            items.stream()
                .filter(Objects::nonNull)
                .map(this::paymentChannelFromPayload)
                .forEach(item -> paymentChannels.put(item.id(), item));
            paymentChannelId.set(maxPaymentChannelId() + 1);
            if (paymentChannels.isEmpty()) {
                seedPaymentChannels(true);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "PAYMENT_CHANNEL", "LIST", ex.getMessage());
            seedPaymentChannels(false);
        }
    }

    private void loadPriceTemplates() {
        if (configPersistenceStore == null) {
            priceTemplates.clear();
            priceTemplates.add(defaultPriceTemplate());
            return;
        }
        try {
            String raw = configPersistenceStore.systemSettings().get(PRICE_TEMPLATE_SETTING_KEY);
            if (!StringUtils.hasText(raw)) {
                priceTemplates.clear();
                priceTemplates.add(defaultPriceTemplate());
                persistPriceTemplates();
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
        if (priceTemplates.isEmpty()) {
            loadPriceTemplates();
        }
    }

    private Optional<List<OrderItem>> persistentOrders() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<OrderItem> items = persistentOrderStore.listOrders();
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "ORDER", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private List<OrderItem> allOrderSnapshots() {
        Map<String, OrderItem> snapshots = new java.util.LinkedHashMap<>();
        persistentOrders().ifPresent(items -> items.forEach(item -> snapshots.put(item.orderNo(), item)));
        orders.values().forEach(item -> snapshots.put(item.orderNo(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
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

    private Optional<PaymentItem> findPaymentSnapshot(String paymentNo) {
        if (!StringUtils.hasText(paymentNo)) {
            return Optional.empty();
        }
        PaymentItem memory = payments.get(paymentNo);
        if (memory != null) {
            return Optional.of(memory);
        }
        Optional<PaymentItem> persistent = persistentPayments().stream()
            .flatMap(List::stream)
            .filter(item -> Objects.equals(item.paymentNo(), paymentNo))
            .findFirst();
        persistent.ifPresent(item -> payments.put(item.paymentNo(), item));
        return persistent;
    }

    private Optional<List<PaymentItem>> persistentPayments() {
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            List<PaymentItem> items = persistentOrderStore.listPayments();
            return Optional.of(items);
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
            return Optional.of(items);
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
            return Optional.of(items);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "REFUND", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private Optional<MemberApiCredentialItem> persistentMemberCredential(Long userId) {
        if (configPersistenceStore == null || userId == null) {
            return Optional.empty();
        }
        try {
            String raw = configPersistenceStore.systemSettings().get("member.credential." + userId);
            if (!StringUtils.hasText(raw)) {
                return Optional.empty();
            }
            Map<String, Object> payload = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            OffsetDateTime createdAt = parseOffsetDateTime(defaultText(payload.get("createdAt"), ""));
            OffsetDateTime lastUsedAt = parseOffsetDateTime(defaultText(payload.get("lastUsedAt"), ""));
            return Optional.of(new MemberApiCredentialItem(
                longValue(payload.get("id"), memberCredentials.values().stream().map(MemberApiCredentialItem::id).max(Long::compareTo).orElse(0L) + 1),
                longValue(payload.get("userId"), userId),
                defaultText(payload.get("appKey"), memberAppKey(userId)),
                memberCredentialSecret(payload),
                defaultText(payload.get("status"), "DISABLED"),
                stringList(payload.get("ipWhitelist")),
                intValue(payload.get("dailyLimit"), 1000),
                createdAt == null ? OffsetDateTime.now() : createdAt,
                lastUsedAt
            ));
        } catch (Exception ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "MEMBER_API", String.valueOf(userId), ex.getMessage());
            return Optional.empty();
        }
    }

    private String memberCredentialSecret(Map<String, Object> payload) {
        String ciphertext = defaultText(payload.get("appSecretCiphertext"), "");
        String nonce = defaultText(payload.get("appSecretNonce"), "");
        if (configPersistenceStore != null && StringUtils.hasText(ciphertext) && StringUtils.hasText(nonce)) {
            return configPersistenceStore.decryptSecretFromSetting(ciphertext, nonce);
        }
        return defaultText(payload.get("appSecret"), memberAppSecret());
    }

    private Optional<List<SmsLogItem>> persistentSmsLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            List<SmsLogItem> items = auditPersistenceStore.listSmsLogs();
            return Optional.of(items);
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
            return Optional.of(items);
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
            return Optional.of(items);
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

    private long maxAdminStaffId() {
        return adminStaff.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(999L);
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

    private List<GoodsChannelItem> allGoodsChannelSnapshots() {
        Map<Long, GoodsChannelItem> snapshots = new java.util.LinkedHashMap<>();
        persistentGoodsChannels().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        goodsChannels.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(GoodsChannelItem::id))
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
        return allGoodsChannelSnapshots().stream()
            .filter(this::isProductMonitorChannel)
            .filter(item -> Objects.equals(item.goodsId(), channel.goodsId()))
            .min(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .map(item -> Objects.equals(item.id(), channel.id()))
            .orElse(false);
    }

    private MonitoredRemoteGoods monitoredRemoteGoods(
        GoodsItem current,
        GoodsChannelItem channel,
        SupplierItem supplier
    ) {
        if (isFuluSupplier(supplier)) {
            throw new IllegalStateException("福禄新平台未提供商品价格/库存接口，商品监控无法实时同步价格库存");
        }
        if (!supportsRemoteGoodsSync(supplier)) {
            throw new IllegalStateException("商品监控暂不支持该供应商的实时价格同步");
        }
        if (isPlaceholderBaseUrl(supplier.baseUrl())) {
            throw new IllegalStateException("供应商未配置真实上游接口地址，无法进行实时价格监控");
        }
        RemoteGoodsItem item = fetchRemoteGoodsSnapshot(supplier, channel.supplierGoodsId(), false);
        return new MonitoredRemoteGoods(
            firstText(item.goodsName(), current.goodsName(), current.name()),
            item.goodsPrice() == null ? current.price() : item.goodsPrice(),
            item.stockNum() == null ? current.stock() : Math.max(0, item.stockNum()),
            remoteGoodsSaleStatus(item),
            remoteGoodsIntegration(supplier, item)
        );
    }

    private GoodsItem applyMonitoredRemoteGoods(GoodsItem current, MonitoredRemoteGoods remote, List<String> changes) {
        String nextName = defaultText(remote.title(), current.goodsName());
        BigDecimal nextPrice = remote.price() == null ? current.price() : remote.price();
        Integer nextStock = remote.stock() == null ? current.stock() : remote.stock();
        String nextStatus = defaultText(remote.status(), current.status());
        List<GoodsIntegrationItem> nextIntegrations = current.integrations();

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
        if (remote.integration() != null) {
            Map<String, GoodsIntegrationItem> integrations = new LinkedHashMap<>();
            normalizeIntegrations(current.integrations()).forEach(item -> integrations.put(integrationKey(item), item));
            String remoteKey = integrationKey(remote.integration());
            GoodsIntegrationItem old = integrations.get(remoteKey);
            if (integrationChanged(old, remote.integration())) {
                changes.add("对接信息已同步真实上游快照");
                integrations.put(remoteKey, remote.integration());
                nextIntegrations = List.copyOf(integrations.values());
            }
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
            current.benefitType(),
            current.benefitBrand(),
            current.priceLimited(),
            current.priceLimitText(),
            current.coverUrl(),
            current.detailImages(),
            current.detailBlocks(),
            nextIntegrations,
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
        if ("alipay".equals(normalized) || "wechat".equals(normalized) || "balance".equals(normalized) || "bank".equals(normalized)) {
            return normalized;
        }
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        return listEnabledPaymentChannels("h5").stream().findFirst().map(PaymentChannelItem::code).orElse("wechat");
    }

    private PaymentChannelItem requireUsablePaymentChannel(String code, String terminal) {
        ensurePaymentChannelsReady();
        String normalizedCode = normalizePaymentChannelCode(code);
        String normalizedTerminal = normalizeTerminal(terminal);
        return paymentChannels.values().stream()
            .filter(item -> Objects.equals(item.code(), normalizedCode))
            .filter(item -> "ENABLED".equalsIgnoreCase(defaultText(item.status(), "ENABLED")))
            .filter(item -> paymentChannelAllowsTerminal(item, normalizedTerminal))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("支付通道不可用，请在后台支付通道管理中启用后再支付"));
    }

    private void ensurePaymentChannelsReady() {
        if (paymentChannels.isEmpty()) {
            loadPaymentChannels();
        }
    }

    private boolean paymentChannelAllowsTerminal(PaymentChannelItem item, String terminal) {
        List<String> terminals = normalizePaymentTerminals(item.terminals());
        return terminals.contains("all") || terminals.contains(normalizeTerminal(terminal));
    }

    private String normalizePaymentChannelCode(String value) {
        return normalize(defaultText(value, "")).replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private String normalizePaymentChannelType(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (Set.of("BALANCE", "WECHAT", "ALIPAY", "BANK", "CUSTOM").contains(normalized)) {
            return normalized;
        }
        return "CUSTOM";
    }

    private String normalizePaymentChannelStatus(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        return "DISABLED".equals(normalized) ? "DISABLED" : "ENABLED";
    }

    private String normalizeTerminal(String value) {
        String normalized = normalize(value);
        if (Set.of("admin", "h5", "web", "api", "all").contains(normalized)) {
            return normalized;
        }
        return "h5";
    }

    private List<String> normalizePaymentTerminals(List<String> values) {
        List<String> normalized = normalizeTextList(values).stream()
            .map(this::normalizeTerminal)
            .distinct()
            .toList();
        return normalized.isEmpty() ? List.of("h5", "web") : normalized;
    }

    private Map<String, String> normalizePaymentChannelConfig(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> config = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalizePaymentConfigKey(key);
            if (StringUtils.hasText(normalizedKey)) {
                config.put(normalizedKey, value == null ? "" : value.trim());
            }
        });
        return config;
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            String normalizedKey = normalizePaymentConfigKey(key == null ? "" : String.valueOf(key));
            if (StringUtils.hasText(normalizedKey)) {
                result.put(normalizedKey, item == null ? "" : String.valueOf(item));
            }
        });
        return result;
    }

    private String normalizePaymentConfigKey(String value) {
        return normalize(defaultText(value, "")).replaceAll("[^a-z0-9_\\-]+", "_");
    }

    private PaymentChannelItem publicPaymentChannel(PaymentChannelItem item) {
        return new PaymentChannelItem(
            item.id(),
            item.code(),
            item.name(),
            item.type(),
            item.terminals(),
            item.status(),
            item.sort(),
            Map.of(),
            item.remark(),
            item.createdAt(),
            item.updatedAt()
        );
    }

    private boolean paymentChannelCodeExists(String code, Long ignoreId) {
        return paymentChannels.values().stream()
            .anyMatch(item -> Objects.equals(item.code(), code) && !Objects.equals(item.id(), ignoreId));
    }

    private long maxPaymentChannelId() {
        return paymentChannels.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
    }

    private PaymentChannelItem sanitizePaymentChannel(PaymentChannelItem item) {
        OffsetDateTime now = OffsetDateTime.now();
        Long id = item.id() == null ? allocateIncrementingId(paymentChannelId, maxPaymentChannelId()) : item.id();
        return new PaymentChannelItem(
            id,
            normalizePaymentChannelCode(item.code()),
            requiredText(item.name(), "支付通道"),
            normalizePaymentChannelType(item.type()),
            normalizePaymentTerminals(item.terminals()),
            normalizePaymentChannelStatus(item.status()),
            item.sort() == null ? (int) (id * 10) : item.sort(),
            normalizePaymentChannelConfig(item.config()),
            defaultText(item.remark(), ""),
            item.createdAt() == null ? now : item.createdAt(),
            item.updatedAt() == null ? now : item.updatedAt()
        );
    }

    private PaymentChannelItem paymentChannelFromPayload(Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.now();
        Long id = longValue(payload.get("id"), allocateIncrementingId(paymentChannelId, maxPaymentChannelId()));
        return new PaymentChannelItem(
            id,
            normalizePaymentChannelCode(defaultText(payload.get("code"), "")),
            requiredText(defaultText(payload.get("name"), ""), "支付通道"),
            normalizePaymentChannelType(defaultText(payload.get("type"), "")),
            normalizePaymentTerminals(stringList(payload.get("terminals"))),
            normalizePaymentChannelStatus(defaultText(payload.get("status"), "")),
            intValue(payload.get("sort"), (int) (id * 10)),
            normalizePaymentChannelConfig(stringMap(payload.get("config"))),
            defaultText(payload.get("remark"), ""),
            Optional.ofNullable(parseOffsetDateTime(defaultText(payload.get("createdAt"), ""))).orElse(now),
            Optional.ofNullable(parseOffsetDateTime(defaultText(payload.get("updatedAt"), ""))).orElse(now)
        );
    }

    private Map<String, Object> paymentChannelPayload(PaymentChannelItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", item.id());
        payload.put("code", item.code());
        payload.put("name", item.name());
        payload.put("type", item.type());
        payload.put("terminals", item.terminals());
        payload.put("status", item.status());
        payload.put("sort", item.sort());
        payload.put("config", item.config());
        payload.put("remark", item.remark());
        payload.put("createdAt", item.createdAt() == null ? "" : item.createdAt().toString());
        payload.put("updatedAt", item.updatedAt() == null ? "" : item.updatedAt().toString());
        return payload;
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

    private void verifyUserPassword(UserItem user, LoginRequest request) {
        if (user == null) {
            return;
        }
        String hash = userPasswordHashes.computeIfAbsent(user.id(), this::persistentUserPasswordHash);
        if (!StringUtils.hasText(hash)) {
            return;
        }
        String password = request == null ? "" : defaultText(request.password(), request.code());
        if (!ADMIN_PASSWORD_ENCODER.matches(password, hash)) {
            throw new IllegalArgumentException("账号或密码不正确");
        }
    }

    private String persistentUserPasswordHash(Long userId) {
        if (configPersistenceStore == null || userId == null) {
            return "";
        }
        try {
            return defaultText(configPersistenceStore.systemSettings().get("user.password." + userId), "");
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "USER_PASSWORD", String.valueOf(userId), persistenceErrorMessage(ex));
            return "";
        }
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

    private byte[] hmacSha256(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("signature calculation failed");
        }
    }

    private String sha256Hex(String payload) {
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(defaultText(payload, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("sha256 calculation failed");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
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

    private String normalizeSalePlatform(String platform) {
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

    private List<GoodsIntegrationItem> normalizeIntegrations(List<GoodsIntegrationItem> integrations) {
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
        if (item == null || item.id() == null) {
            return item;
        }
        List<GoodsIntegrationItem> savedIntegrations = normalizeIntegrations(item.integrations());
        Set<String> savedIntegrationKeys = savedIntegrations.stream()
            .map(this::integrationKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<GoodsIntegrationItem> channelIntegrations = allGoodsChannelSnapshots().stream()
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

    private boolean integrationChanged(GoodsIntegrationItem oldItem, GoodsIntegrationItem nextItem) {
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
                    productMonitorStates.remove(channel.id());
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
                    firstText(integration.supplierName(), "", "货源渠道"),
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
        SupplierItem supplier = suppliers.get(channel.supplierId());
        String supplierName = firstText(channel.supplierName(), supplier == null ? "" : supplier.name(), "货源渠道");
        String platformCode = supplier == null ? String.valueOf(channel.supplierId()) : defaultText(supplier.platformType(), String.valueOf(channel.supplierId()));
        Optional<RemoteGoodsItem> remote = Optional.ofNullable(remoteGoodsSyncResults.get(channel.supplierId()))
            .flatMap(result -> exactRemoteGoods(result.items(), channel.supplierGoodsId()));
        if (supplier != null && remote.isPresent()) {
            GoodsIntegrationItem snapshot = remoteGoodsIntegration(supplier, remote.get());
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

    private Optional<RemoteGoodsItem> exactRemoteGoods(List<RemoteGoodsItem> items, String supplierGoodsId) {
        String normalizedId = defaultText(supplierGoodsId, "").trim();
        if (!StringUtils.hasText(normalizedId) || items == null) {
            return Optional.empty();
        }
        return items.stream()
            .filter(item -> Objects.equals(defaultText(item.supplierGoodsId(), "").trim(), normalizedId))
            .findFirst();
    }

    private RemoteGoodsItem fetchRemoteGoodsSnapshot(SupplierItem supplier, String supplierGoodsId) {
        return fetchRemoteGoodsSnapshot(supplier, supplierGoodsId, true);
    }

    private RemoteGoodsItem fetchRemoteGoodsSnapshot(SupplierItem supplier, String supplierGoodsId, boolean preferCachedSnapshot) {
        RemoteGoodsSyncResult result;
        if (preferCachedSnapshot) {
            Optional<RemoteGoodsItem> cached = latestRemoteGoods(supplier.id())
                .flatMap(snapshot -> exactRemoteGoods(snapshot.items(), supplierGoodsId));
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        if (supportsRemoteGoodsSync(supplier)) {
            result = fetchIntegratedRemoteGoods(supplier, 0L, supplierGoodsId, 1, 100);
            Optional<RemoteGoodsItem> remote = exactRemoteGoods(result.items(), supplierGoodsId);
            if (remote.isPresent()) {
                remoteGoodsSyncResults.put(supplier.id(), result);
                persistSupplier(supplier.withLastSyncAt(result.syncedAt()));
                return remote.get();
            }
            remote = isKasushouSupplier(supplier)
                ? fetchKasushouGoodsByScanning(supplier, supplierGoodsId)
                : (isKakayunSupplier(supplier) ? fetchKakayunGoodsSnapshot(supplier, supplierGoodsId) : Optional.empty());
            if (remote.isPresent()) {
                return remote.get();
            }
        } else if (isFuluSupplier(supplier) || isFengzhushouSupplier(supplier)) {
            return manualRemoteGoodsSnapshot(supplier, supplierGoodsId);
        }
        throw new IllegalArgumentException("未找到真实上游商品: " + supplierGoodsId);
    }

    private RemoteGoodsItem manualRemoteGoodsSnapshot(SupplierItem supplier, String supplierGoodsId) {
        GoodsChannelItem channel = sourceConnectedChannel(supplier.id(), supplierGoodsId).orElse(null);
        GoodsItem localGoods = channel == null ? null : findGoodsSnapshot(channel.goodsId()).orElse(null);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("platform", defaultText(supplier.platformType(), ""));
        raw.put("supplier_goods_id", supplierGoodsId);
        raw.put("source", "manual_binding");
        raw.put("note", platformLabelForManualSupplier(supplier) + "未提供商品详情/价格/库存接口，商品信息以本地商品配置为准");
        return new RemoteGoodsItem(
            supplierGoodsId,
            localGoods == null ? platformLabelForManualSupplier(supplier) + "商品 " + supplierGoodsId : localGoods.goodsName(),
            localGoods != null && localGoods.type() == GoodsType.CARD ? "CARD" : "DIRECT",
            "",
            "手动绑定",
            localGoods == null ? BigDecimal.ZERO : localGoods.price(),
            localGoods == null ? BigDecimal.ZERO : localGoods.originalPrice(),
            localGoods == null ? 0 : localGoods.stock(),
            localGoods == null ? "UNKNOWN" : localGoods.status(),
            true,
            false,
            channel != null,
            channel == null ? null : channel.goodsId(),
            localGoods == null ? "" : localGoods.goodsName(),
            channel == null ? null : channel.id(),
            raw
        );
    }

    private RemoteGoodsItem chengquanRemoteGoodsItem(
        Long supplierId,
        JsonNode node,
        Map<String, String> categoryNames,
        String selectedCategoryId,
        String selectedCategoryName
    ) {
        String supplierGoodsId = textValue(node, "product_id", "productId", "goods_id", "goodsId", "id");
        String categoryId = firstText(textValue(node, "type_id", "typeId", "category_id", "categoryId"), selectedCategoryId, "");
        String categoryName = firstText(
            textValue(node, "type_name", "typeName", "category_name", "categoryName", "brand_name", "brandName"),
            categoryNames.getOrDefault(categoryId, ""),
            selectedCategoryName
        );
        String goodsName = firstText(textValue(node, "product_name", "productName", "goods_name", "goodsName", "name", "title"), "鼎信橙券商品 " + supplierGoodsId, "");
        BigDecimal price = decimalValue(node, "price", "sale_price", "salePrice", "cost_price", "costPrice", "settle_price", "settlePrice");
        BigDecimal face = decimalValue(node, "face_value", "faceValue", "market_price", "marketPrice", "par_value", "parValue");
        int stock = intValue(firstExisting(node, "stock", "stock_num", "stockNum", "num"), 0);
        String status = firstText(textValue(node, "status", "state", "is_sale", "isSale"), stock > 0 ? "ON_SALE" : "UNKNOWN", "");
        Map<String, Object> raw = OBJECT_MAPPER.convertValue(node, MAP_TYPE);
        return new RemoteGoodsItem(
            supplierGoodsId,
            goodsName,
            "CARD",
            categoryId,
            categoryName,
            price,
            face,
            stock,
            status,
            stock > 0,
            stock <= 0,
            sourceConnectedChannel(supplierId, supplierGoodsId).isPresent(),
            null,
            "",
            null,
            raw
        );
    }

    private RemoteGoodsItem fanchenRemoteGoodsItem(Long supplierId, JsonNode node) {
        String supplierGoodsId = textValue(node, "product_id", "productid", "productId");
        String goodsName = firstText(textValue(node, "product_name", "productname", "productName"), "浙江梵尘商品 " + supplierGoodsId, "");
        String categoryId = textValue(node, "category_id", "categoryId");
        String categoryName = textValue(node, "category_name", "categoryName");
        BigDecimal price = decimalValue(node, "product_price", "productPrice", "price");
        BigDecimal face = decimalValue(node, "par_value", "parValue", "faceValue");
        String goodsType = normalize(goodsName).contains("卡密") ? "CARD" : "DIRECT";
        Map<String, Object> raw = OBJECT_MAPPER.convertValue(node, MAP_TYPE);
        return new RemoteGoodsItem(
            supplierGoodsId,
            goodsName,
            goodsType,
            categoryId,
            categoryName,
            price,
            face,
            9999,
            "ON_SALE",
            true,
            false,
            sourceConnectedChannel(supplierId, supplierGoodsId).isPresent(),
            null,
            "",
            null,
            raw
        );
    }

    private RemoteGoodsItem jingzhaoRemoteGoodsItem(Long supplierId, JsonNode node) {
        String supplierGoodsId = textValue(node, "id", "product_id", "productId");
        int type = intValue(firstExisting(node, "type", "product_type", "productType"), 1);
        int stockState = intValue(firstExisting(node, "stock_state", "stockState"), 1);
        int supplyState = intValue(firstExisting(node, "supply_state", "supplyState"), 1);
        int holdState = intValue(firstExisting(node, "hold_state", "holdState"), 1);
        boolean canBuy = stockState == 1 && supplyState == 1 && holdState == 1;
        String productName = firstText(textValue(node, "product_name", "productName"), "", "");
        String specName = textValue(node, "name", "spec_name", "specName");
        String goodsName = StringUtils.hasText(specName) && !productName.contains(specName)
            ? productName + " " + specName
            : firstText(productName, "京兆云商品 " + supplierGoodsId, "");
        GoodsChannelItem channel = sourceConnectedChannel(supplierId, supplierGoodsId).orElse(null);
        GoodsItem localGoods = channel == null ? null : findGoodsSnapshot(channel.goodsId()).orElse(null);
        Map<String, Object> raw = OBJECT_MAPPER.convertValue(node, MAP_TYPE);
        return new RemoteGoodsItem(
            supplierGoodsId,
            goodsName,
            type == 2 || type == 3 ? "CARD" : "DIRECT",
            String.valueOf(type),
            jingzhaoGoodsTypeLabel(type == 2 || type == 3 ? "CARD" : "DIRECT"),
            decimalValue(node, "price", "sale_price", "salePrice"),
            decimalValue(node, "face_value", "faceValue", "market_price", "marketPrice"),
            stockState == 1 ? 9999 : 0,
            canBuy ? "ON_SALE" : "OFF_SALE",
            canBuy,
            !canBuy,
            channel != null,
            channel == null ? null : channel.goodsId(),
            localGoods == null ? "" : localGoods.goodsName(),
            channel == null ? null : channel.id(),
            raw
        );
    }

    private String jingzhaoGoodsTypeLabel(String type) {
        return switch (defaultText(type, "").trim().toUpperCase(Locale.ROOT)) {
            case "CARD" -> "卡密/卡券";
            case "DIRECT" -> "直充";
            default -> "商品";
        };
    }

    private Optional<RemoteGoodsItem> fetchKakayunGoodsSnapshot(SupplierItem supplier, String supplierGoodsId) {
        if (!StringUtils.hasText(supplierGoodsId)) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsid", kasushouGoodsId(supplierGoodsId));
        JsonNode root = kakayunPostJson(supplier, "/dockapiv3/goods/details", body, "goods detail sync");
        ensureKakayunOk(root, "goods detail sync");
        JsonNode data = root.path("data");
        JsonNode node = data.isArray() && data.size() > 0 ? data.get(0) : data;
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        RemoteGoodsItem item = kakayunRemoteGoodsItem(supplier.id(), node, Map.of(), "", "");
        OffsetDateTime syncedAt = OffsetDateTime.now();
        RemoteGoodsSyncResult result = new RemoteGoodsSyncResult(
            supplier.id(),
            syncedAt,
            1,
            List.of(item),
            List.of(),
            1,
            1,
            "synced kakayun goods detail " + supplierGoodsId
        );
        remoteGoodsSyncResults.put(supplier.id(), result);
        persistSupplier(supplier.withLastSyncAt(syncedAt));
        return Optional.of(item);
    }

    private Optional<RemoteGoodsItem> fetchKasushouGoodsByScanning(SupplierItem supplier, String supplierGoodsId) {
        int page = 1;
        int limit = 100;
        int total = Integer.MAX_VALUE;
        int maxPages = 200;
        while (page <= maxPages && (page - 1) * limit < total) {
            RemoteGoodsSyncResult result = fetchKasushouGoods(supplier, 0L, "", page, limit);
            total = result.total() == null ? 0 : result.total();
            Optional<RemoteGoodsItem> remote = exactRemoteGoods(result.items(), supplierGoodsId);
            if (remote.isPresent()) {
                remoteGoodsSyncResults.put(supplier.id(), result);
                persistSupplier(supplier.withLastSyncAt(result.syncedAt()));
                return remote;
            }
            if (result.items().isEmpty()) {
                break;
            }
            page++;
        }
        return Optional.empty();
    }

    private GoodsIntegrationItem remoteGoodsIntegration(SupplierItem supplier, RemoteGoodsItem remote) {
        String status = remoteGoodsSaleStatus(remote);
        return new GoodsIntegrationItem(
            "remote-" + supplier.id() + "-" + remote.supplierGoodsId(),
            supplier.id(),
            supplier.name(),
            defaultText(supplier.platformType(), String.valueOf(supplier.id())),
            defaultText(remote.supplierGoodsId(), ""),
            defaultText(remote.goodsName(), ""),
            remote.goodsPrice() == null ? BigDecimal.ZERO : remote.goodsPrice(),
            status,
            remote.stockNum() == null ? 0 : remote.stockNum(),
            defaultText(remote.goodsName(), ""),
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            true
        );
    }

    private String integrationKey(GoodsIntegrationItem item) {
        return defaultText(item.supplierId() == null ? "" : String.valueOf(item.supplierId()), item.platformCode())
            + ":" + defaultText(item.supplierGoodsId(), "");
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

    private List<String> normalizeGoodsTags(List<String> values) {
        return normalizeTextList(values).stream()
            .filter(value -> !LEGACY_SYSTEM_GOODS_TAGS.contains(value.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private AdminStaffItem adminStaffFromPayload(Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AdminStaffItem(
            longValue(payload.get("id"), null),
            normalize(defaultText(payload.get("account"), "")),
            defaultText(payload.get("nickname"), "员工账号"),
            normalizeAdminStaffStatus(payload.get("status")),
            normalizeAdminPermissions(stringList(payload.get("permissions"))),
            Optional.ofNullable(parseOffsetDateTime(defaultText(payload.get("createdAt"), ""))).orElse(now),
            Optional.ofNullable(parseOffsetDateTime(defaultText(payload.get("updatedAt"), ""))).orElse(now)
        );
    }

    private void validateAdminStaffAccount(String account, Long excludeId) {
        if (Objects.equals(account, adminUsername)) {
            throw new IllegalArgumentException("员工账号不能与超级管理员账号重复");
        }
        boolean exists = adminStaff.values().stream()
            .filter(item -> !Objects.equals(item.id(), excludeId))
            .anyMatch(item -> Objects.equals(normalize(item.account()), account));
        if (exists) {
            throw new IllegalArgumentException("员工账号已存在");
        }
    }

    private void validateSuperAdminAccount(String account) {
        boolean exists = adminStaff.values().stream()
            .anyMatch(item -> Objects.equals(normalize(item.account()), account));
        if (exists) {
            throw new IllegalArgumentException("超级管理员账号不能与员工账号重复");
        }
    }

    private String normalizeAdminStaffStatus(Object value) {
        String status = normalize(defaultText(value, "ENABLED"));
        return "disabled".equals(status) ? "DISABLED" : "ENABLED";
    }

    private List<String> normalizeAdminPermissions(List<String> values) {
        Set<String> allowed = new LinkedHashSet<>(ALL_ADMIN_PERMISSIONS);
        List<String> permissions = normalizeTextList(values).stream()
            .filter(allowed::contains)
            .toList();
        return permissions.isEmpty() ? List.of("dashboard:read") : permissions;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return List.of(text.trim());
        }
        return List.of();
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

    private void validatePriceLimitPermission(UserItem user, GoodsItem item) {
        UserGroupItem group = findUserGroupSnapshot(user.groupId() == null ? 1L : user.groupId()).orElse(null);
        if (!priceLimitAllowed(item, group)) {
            throw new IllegalStateException(priceLimitNotice(group == null ? "" : group.priceLimitNotice()));
        }
    }

    private boolean priceLimitAllowed(GoodsItem item, UserGroupItem group) {
        if (item == null || !StringUtils.hasText(defaultText(item.priceLimitText(), ""))) {
            return true;
        }
        return group == null || group.priceLimitEnabled();
    }

    private String priceLimitNotice(String value) {
        String notice = defaultText(value, "").trim();
        return StringUtils.hasText(notice) ? notice : DEFAULT_PRICE_LIMIT_NOTICE;
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

    private UserItem createUserFromAccount(String account) {
        validateRegistration(account);
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

    private AuthSession<UserItem> registerUser(UserAuthRequest request, String terminal, String account) {
        if (allUserSnapshots().stream().anyMatch(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))) {
            throw new IllegalStateException("账号已存在，请直接登录");
        }
        validateRegistration(account);
        if (isRegistrationSmsCodeRequired()) {
            verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        }
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (StringUtils.hasText(password)) {
            validateNewPassword(password, confirmPassword);
        }
        UserItem user = createUserFromAccount(account);
        if (StringUtils.hasText(password)) {
            userPasswordHashes.put(user.id(), ADMIN_PASSWORD_ENCODER.encode(password));
            persistRuntimeSetting("user.password." + user.id(), userPasswordHashes.get(user.id()));
        }
        UserItem next = withUserLastLoginAt(user, OffsetDateTime.now());
        users.put(next.id(), next);
        persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        appendOperation("USER_REGISTER", "USER", String.valueOf(next.id()), terminal + ":" + account);
        return new AuthSession<>(token, withGroupName(next));
    }

    private AuthSession<UserItem> resetUserPassword(UserAuthRequest request, String terminal, String account) {
        UserItem user = allUserSnapshots().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        validateNewPassword(password, confirmPassword);
        String nextHash = ADMIN_PASSWORD_ENCODER.encode(password);
        userPasswordHashes.put(user.id(), nextHash);
        persistRuntimeSetting("user.password." + user.id(), nextHash);
        invalidateUserTokens(user.id());
        UserItem next = withUserLastLoginAt(user, OffsetDateTime.now());
        users.put(next.id(), next);
        persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        appendOperation("USER_PASSWORD_RESET", "USER", String.valueOf(next.id()), terminal + ":" + account);
        return new AuthSession<>(token, withGroupName(next));
    }

    private String issueUserToken(Long userId) {
        String token = "h5_" + UUID.randomUUID();
        userTokens.put(token, userId);
        userTokenExpiresAt.put(token, OffsetDateTime.now().plus(USER_TOKEN_TTL));
        if (securityStateStore != null) {
            securityStateStore.storeUserToken(token, userId, USER_TOKEN_TTL);
        }
        return token;
    }

    private void invalidateUserTokens(Long userId) {
        userTokens.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), userId));
        userTokenExpiresAt.keySet().removeIf(token -> !userTokens.containsKey(token));
        if (securityStateStore != null) {
            securityStateStore.invalidateUserTokens(userId);
        }
    }

    private void validateNewPassword(String password, String confirmPassword) {
        if (!StringUtils.hasText(password) || password.length() < 6) {
            throw new IllegalArgumentException("密码至少需要 6 位");
        }
        if (!Objects.equals(password, confirmPassword)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
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
        if (findUserGroupSnapshot(nextGroupId).isPresent()) {
            return nextGroupId;
        }
        return listUserGroups().stream()
            .filter(UserGroupItem::defaultGroup)
            .map(UserGroupItem::id)
            .findFirst()
            .or(() -> listUserGroups().stream()
                .filter(group -> "ENABLED".equalsIgnoreCase(defaultText(group.status(), "")))
                .map(UserGroupItem::id)
                .findFirst())
            .orElse(1L);
    }

    private void validateRegistration(String account) {
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
    }

    private boolean isRegistrationSmsCodeRequired() {
        return "MOBILE".equals(normalizeRegistrationType(systemSetting.registrationType()));
    }

    private String normalizeRechargeFieldCode(String value) {
        return normalize(value)
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private boolean isValidRechargeFieldCode(String value) {
        return value != null && value.matches("^[a-z][a-z0-9_]*$");
    }

    private List<String> validateEnabledRechargeFieldCodes(List<String> codes) {
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

    private List<String> normalizedBenefitDurations(List<String> durations, String title) {
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

    private String inferredPriceLimitText(String... titles) {
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

    private boolean titleContainsPriceLimited(String title) {
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
        persistMemberCredential(item);
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

    private boolean isUserSmsLoginRequired(String terminal) {
        if (!smsLoginSetting.enabled()) {
            return false;
        }
        return switch (normalizeTerminal(terminal)) {
            case "web" -> smsLoginSetting.webLoginEnabled();
            case "api" -> false;
            default -> smsLoginSetting.h5LoginEnabled();
        };
    }

    private String sendLoginSmsCode(String terminal, String mobile, String purpose) {
        String key = verificationKey(purpose, terminal, mobile);
        SmsVerificationCode existing = findSmsVerificationCode(key);
        OffsetDateTime now = OffsetDateTime.now();
        if (existing != null && existing.sentAt() != null && existing.sentAt().plusSeconds(smsLoginSetting.cooldownSeconds()).isAfter(now)) {
            throw new IllegalStateException("验证码发送太频繁，请稍后再试");
        }
        String code = nextSmsCode(smsLoginSetting.codeLength());
        String content = "您的喜易云登录验证码为：" + code + "，" + (smsLoginSetting.ttlSeconds() / 60) + "分钟内有效。";
        String status = "SENT";
        String error = "";
        try {
            sendSmsByProvider(mobile, code, content);
        } catch (RuntimeException ex) {
            status = "FAILED";
            error = ex.getMessage();
        }
        Long id = smsLogId.getAndIncrement();
        SmsLogItem log = new SmsLogItem(id, "LOGIN", mobile, purpose + ":" + normalizeTerminal(terminal), content, status, error, now);
        smsLogs.put(id, log);
        persistSmsLog(log);
        if (!"SENT".equals(status)) {
            throw new IllegalStateException("短信发送失败：" + error);
        }
        OffsetDateTime expiresAt = now.plusSeconds(smsLoginSetting.ttlSeconds());
        SmsVerificationCode next = new SmsVerificationCode(key, code, expiresAt, now, 0, false);
        smsVerificationCodes.put(key, next);
        if (securityStateStore != null) {
            securityStateStore.storeSmsCode(key, code, expiresAt, now);
        }
        return "验证码已发送";
    }

    private void verifyLoginSmsCode(String key, String code) {
        Optional<RedisSecurityStateStore.SmsCodeSnapshot> redisSnapshot = securityStateStore == null
            ? Optional.empty()
            : securityStateStore.loadSmsCode(key);
        if (redisSnapshot.isPresent() && redisSnapshot.get().found()) {
            verifyRedisLoginSmsCode(redisSnapshot.get(), code);
            return;
        }
        SmsVerificationCode current = smsVerificationCodes.get(key);
        OffsetDateTime now = OffsetDateTime.now();
        if (current == null || current.used() || current.expiresAt() == null || !current.expiresAt().isAfter(now)) {
            smsVerificationCodes.remove(key);
            if (securityStateStore != null) {
                securityStateStore.deleteSmsCode(key);
            }
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        int attempts = current.attempts() + 1;
        if (attempts > smsLoginSetting.maxAttempts()) {
            smsVerificationCodes.remove(key);
            if (securityStateStore != null) {
                securityStateStore.deleteSmsCode(key);
            }
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!Objects.equals(current.code(), defaultText(code, "").trim())) {
            SmsVerificationCode next = new SmsVerificationCode(key, current.code(), current.expiresAt(), current.sentAt(), attempts, false);
            smsVerificationCodes.put(key, next);
            throw new IllegalArgumentException("验证码不正确");
        }
        SmsVerificationCode next = new SmsVerificationCode(key, current.code(), current.expiresAt(), current.sentAt(), attempts, true);
        smsVerificationCodes.put(key, next);
    }

    private SmsVerificationCode findSmsVerificationCode(String key) {
        Optional<RedisSecurityStateStore.SmsCodeSnapshot> redisSnapshot = securityStateStore == null
            ? Optional.empty()
            : securityStateStore.loadSmsCode(key);
        if (redisSnapshot.isPresent() && redisSnapshot.get().found()) {
            RedisSecurityStateStore.SmsCodeSnapshot snapshot = redisSnapshot.get();
            return new SmsVerificationCode(key, "", snapshot.expiresAt(), snapshot.sentAt(), snapshot.attempts(), snapshot.used());
        }
        return smsVerificationCodes.get(key);
    }

    private void verifyRedisLoginSmsCode(RedisSecurityStateStore.SmsCodeSnapshot current, String code) {
        OffsetDateTime now = OffsetDateTime.now();
        if (current.used() || current.expiresAt() == null || !current.expiresAt().isAfter(now)) {
            securityStateStore.deleteSmsCode(current.key());
            smsVerificationCodes.remove(current.key());
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        int attempts = current.attempts() + 1;
        if (attempts > smsLoginSetting.maxAttempts()) {
            securityStateStore.deleteSmsCode(current.key());
            smsVerificationCodes.remove(current.key());
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!securityStateStore.matchesSmsCode(current, code)) {
            RedisSecurityStateStore.SmsCodeSnapshot next = current.withAttempts(attempts);
            securityStateStore.storeSmsCode(next);
            SmsVerificationCode local = smsVerificationCodes.get(current.key());
            if (local != null) {
                smsVerificationCodes.put(current.key(), new SmsVerificationCode(
                    local.key(), local.code(), local.expiresAt(), local.sentAt(), attempts, false
                ));
            }
            throw new IllegalArgumentException("验证码不正确");
        }
        securityStateStore.storeSmsCode(current.markUsed(attempts));
        SmsVerificationCode local = smsVerificationCodes.get(current.key());
        if (local != null) {
            smsVerificationCodes.put(current.key(), new SmsVerificationCode(
                local.key(), local.code(), local.expiresAt(), local.sentAt(), attempts, true
            ));
        }
    }

    private void verifySliderToken(String token) {
        String cleanToken = defaultText(token, "").trim();
        if (StringUtils.hasText(cleanToken) && securityStateStore != null) {
            Optional<Boolean> redisConsumed = securityStateStore.consumeSliderToken(cleanToken);
            if (redisConsumed.orElse(false)) {
                sliderTokens.remove(cleanToken);
                return;
            }
        }
        OffsetDateTime expiresAt = sliderTokens.remove(cleanToken);
        if (!StringUtils.hasText(cleanToken) || expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("滑块验证已失效，请重新验证");
        }
    }

    private void verifyHumanCaptchaIfRequired(String terminal, String ticket, String randstr, String clientIp) {
        String cleanTerminal = normalizeTerminal(terminal);
        if (!isCaptchaRequired(cleanTerminal)) {
            return;
        }
        if (!StringUtils.hasText(ticket) || !StringUtils.hasText(randstr)) {
            throw new IllegalArgumentException("请先完成人机验证");
        }
        switch (normalizeCaptchaProvider(captchaSetting.provider())) {
            case "GENERIC" -> verifyGenericCaptcha(ticket, randstr, clientIp);
            case "TURNSTILE" -> verifyTurnstileCaptcha(ticket, clientIp);
            default -> verifyTencentCaptcha(ticket, randstr, clientIp);
        }
    }

    private boolean isCaptchaRequired(String terminal) {
        if (!captchaSetting.enabled()) {
            return false;
        }
        return switch (normalizeTerminal(terminal)) {
            case "admin" -> captchaSetting.adminLoginEnabled();
            case "web" -> captchaSetting.webLoginEnabled();
            default -> captchaSetting.h5LoginEnabled();
        };
    }

    private String verificationKey(String purpose, String terminal, String target) {
        return normalize(purpose) + ":" + normalize(terminal) + ":" + normalize(target);
    }

    private String nextSmsCode(int length) {
        int safeLength = clampInt(length, 4, 8);
        int bound = (int) Math.pow(10, safeLength);
        int floor = (int) Math.pow(10, safeLength - 1);
        return String.valueOf(floor + SECURE_RANDOM.nextInt(bound - floor));
    }

    private boolean isMobile(String value) {
        return defaultText(value, "").matches("^1[3-9]\\d{9}$");
    }

    private void sendSmsByProvider(String mobile, String code, String content) {
        switch (normalizeSmsProvider(smsLoginSetting.provider())) {
            case "GENERIC" -> sendGenericSms(mobile, code, content);
            case "ALIYUN" -> sendAliyunSms(mobile, code);
            default -> sendTencentSms(mobile, code);
        }
    }

    private void sendGenericSms(String mobile, String code, String content) {
        Map<String, String> config = smsLoginSetting.genericConfig();
        String url = defaultText(config.get("url"), "");
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("通用短信接口 URL 未配置");
        }
        String method = normalize(defaultText(config.get("method"), "POST")).toUpperCase(Locale.ROOT);
        String body = applySmsTemplate(defaultText(config.get("body_template"), "{\"mobile\":\"{mobile}\",\"code\":\"{code}\",\"content\":\"{content}\"}"), mobile, code, content);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12));
        builder.header("Content-Type", defaultText(config.get("content_type"), "application/json; charset=UTF-8"));
        stringMap(config.get("headers")).forEach(builder::header);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = sendHttp(builder.build(), "generic sms");
        String successKeyword = defaultText(config.get("success_keyword"), "");
        if (response.statusCode() >= 400 || (StringUtils.hasText(successKeyword) && !response.body().contains(successKeyword))) {
            throw new IllegalStateException("通用短信接口返回异常：" + response.statusCode() + " " + response.body());
        }
    }

    private void sendTencentSms(String mobile, String code) {
        Map<String, String> config = smsLoginSetting.tencentConfig();
        String secretId = defaultText(config.get("secret_id"), "");
        String secretKey = defaultText(config.get("secret_key"), "");
        String sdkAppId = defaultText(config.get("sdk_app_id"), "");
        String signName = defaultText(config.get("sign_name"), "");
        String templateId = defaultText(config.get("template_id"), "");
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey) || !StringUtils.hasText(sdkAppId) || !StringUtils.hasText(signName) || !StringUtils.hasText(templateId)) {
            throw new IllegalStateException("腾讯云短信配置不完整");
        }
        String region = defaultText(config.get("region"), "ap-guangzhou");
        String params = applySmsTemplate(defaultText(config.get("template_param_json"), "[\"{code}\"]"), mobile, code, code);
        String payload = "{\"PhoneNumberSet\":[\"+86" + mobile + "\"],\"SmsSdkAppId\":\"" + jsonEscape(sdkAppId) + "\",\"SignName\":\"" + jsonEscape(signName) + "\",\"TemplateId\":\"" + jsonEscape(templateId) + "\",\"TemplateParamSet\":" + params + "}";
        long timestamp = Instant.now().getEpochSecond();
        String authorization = tencentAuthorization(secretId, secretKey, "sms", "POST", "sms.tencentcloudapi.com", payload, timestamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://sms.tencentcloudapi.com"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-TC-Action", "SendSms")
            .header("X-TC-Version", "2021-01-11")
            .header("X-TC-Region", region)
            .header("X-TC-Timestamp", String.valueOf(timestamp))
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = sendHttp(request, "tencent sms");
        if (response.statusCode() >= 400 || response.body().contains("\"Error\"")) {
            throw new IllegalStateException("腾讯云短信返回异常：" + response.body());
        }
    }

    private void verifyTencentCaptcha(String ticket, String randstr, String clientIp) {
        Map<String, String> config = captchaSetting.tencentConfig();
        String secretId = defaultText(config.get("secret_id"), "");
        String secretKey = defaultText(config.get("secret_key"), "");
        String captchaAppId = defaultText(config.get("captcha_app_id"), "");
        String appSecretKey = defaultText(config.get("app_secret_key"), "");
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey) || !StringUtils.hasText(captchaAppId) || !StringUtils.hasText(appSecretKey)) {
            throw new IllegalStateException("腾讯云人机验证配置不完整");
        }
        HttpResponse<String> response = requestTencentCaptcha(config, ticket, randstr, clientIp);
        if (response.statusCode() >= 400 || response.body().contains("\"Error\"")) {
            throw new IllegalStateException("腾讯云人机验证返回异常：" + response.body());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response.body()).path("Response");
            int captchaCode = root.path("CaptchaCode").asInt(-1);
            int evilLevel = root.path("EvilLevel").asInt(100);
            if (captchaCode != 1 || evilLevel >= 100) {
                throw new IllegalArgumentException("人机验证未通过，请重新验证");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("腾讯云人机验证响应解析失败");
        }
    }

    private void verifyTurnstileCaptcha(String token, String clientIp) {
        Map<String, String> config = captchaSetting.turnstileConfig();
        String secretKey = defaultText(config.get("secret_key"), "");
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("Cloudflare Turnstile 配置不完整");
        }
        HttpResponse<String> response = requestTurnstileCaptcha(config, token, clientIp);
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Cloudflare Turnstile 返回异常：HTTP " + response.statusCode() + " " + response.body());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                throw new IllegalArgumentException("人机验证未通过，请重新验证");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cloudflare Turnstile 响应解析失败");
        }
    }

    private String testTencentCaptchaSetting(Map<String, String> config) {
        HttpResponse<String> response = requestTencentCaptcha(config, "xiyiyun_config_test_ticket", "xiyiyun_config_test_randstr", "127.0.0.1");
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("腾讯云人机验证接口连接失败：HTTP " + response.statusCode() + " " + response.body());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response.body()).path("Response");
            JsonNode error = root.path("Error");
            if (!error.isMissingNode() && !error.isNull()) {
                String code = textValue(error, "Code", "code");
                String message = textValue(error, "Message", "message");
                if (code.toLowerCase(Locale.ROOT).contains("auth")
                    || code.toLowerCase(Locale.ROOT).contains("credential")
                    || code.toLowerCase(Locale.ROOT).contains("signature")
                    || code.toLowerCase(Locale.ROOT).contains("secret")) {
                    throw new IllegalStateException("腾讯云密钥或签名配置异常：" + code + " " + message);
                }
                return "腾讯云接口已连通，密钥签名可用；测试票据无效属正常现象。";
            }
            return "腾讯云接口已连通，人机验证配置完整。";
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("腾讯云人机验证测试响应解析失败");
        }
    }

    private String testTurnstileCaptchaSetting(Map<String, String> config) {
        HttpResponse<String> response = requestTurnstileCaptcha(config, "xiyiyun_config_test_token", "127.0.0.1");
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Cloudflare Turnstile 接口连接失败：HTTP " + response.statusCode() + " " + response.body());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode errors = root.path("error-codes");
            if (errors.isArray()) {
                for (JsonNode error : errors) {
                    String code = error.asText("");
                    if (code.contains("secret")) {
                        throw new IllegalStateException("Cloudflare Turnstile Secret Key 配置异常：" + code);
                    }
                }
            }
            return "Cloudflare Turnstile 接口已连通；测试 token 无效属正常现象。";
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cloudflare Turnstile 测试响应解析失败");
        }
    }

    private HttpResponse<String> requestTurnstileCaptcha(Map<String, String> config, String token, String clientIp) {
        String secretKey = defaultText(config.get("secret_key"), "");
        String form = "secret=" + percentEncode(secretKey)
            + "&response=" + percentEncode(token)
            + "&remoteip=" + percentEncode(defaultText(clientIp, ""));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
        return sendHttp(request, "cloudflare turnstile");
    }

    private HttpResponse<String> requestTencentCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp) {
        String secretId = defaultText(config.get("secret_id"), "");
        String secretKey = defaultText(config.get("secret_key"), "");
        String captchaAppId = defaultText(config.get("captcha_app_id"), "");
        String appSecretKey = defaultText(config.get("app_secret_key"), "");
        String region = defaultText(config.get("region"), "ap-guangzhou");
        String payload = "{\"CaptchaType\":9,\"Ticket\":\"" + jsonEscape(ticket) + "\",\"UserIp\":\"" + jsonEscape(defaultText(clientIp, "127.0.0.1")) + "\",\"Randstr\":\"" + jsonEscape(randstr) + "\",\"CaptchaAppId\":" + numberJson(captchaAppId) + ",\"AppSecretKey\":\"" + jsonEscape(appSecretKey) + "\"}";
        long timestamp = Instant.now().getEpochSecond();
        String authorization = tencentAuthorization(secretId, secretKey, "captcha", "POST", "captcha.tencentcloudapi.com", payload, timestamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://captcha.tencentcloudapi.com"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-TC-Action", "DescribeCaptchaResult")
            .header("X-TC-Version", "2019-07-22")
            .header("X-TC-Region", region)
            .header("X-TC-Timestamp", String.valueOf(timestamp))
            .header("Authorization", authorization)
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();
        return sendHttp(request, "tencent captcha");
    }

    private void verifyGenericCaptcha(String ticket, String randstr, String clientIp) {
        Map<String, String> config = captchaSetting.genericConfig();
        String url = defaultText(config.get("url"), "");
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("通用人机验证接口 URL 未配置");
        }
        String body = defaultText(config.get("body_template"), "{\"ticket\":\"{ticket}\",\"randstr\":\"{randstr}\",\"ip\":\"{ip}\"}")
            .replace("{ticket}", jsonEscape(ticket))
            .replace("{randstr}", jsonEscape(randstr))
            .replace("{ip}", jsonEscape(defaultText(clientIp, "")));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", defaultText(config.get("content_type"), "application/json; charset=UTF-8"));
        stringMap(config.get("headers")).forEach(builder::header);
        String method = normalize(defaultText(config.get("method"), "POST")).toUpperCase(Locale.ROOT);
        HttpResponse<String> response = sendHttp("GET".equals(method) ? builder.GET().build() : builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), "generic captcha");
        String successKeyword = defaultText(config.get("success_keyword"), "");
        if (response.statusCode() >= 400 || (StringUtils.hasText(successKeyword) && !response.body().contains(successKeyword))) {
            throw new IllegalArgumentException("人机验证未通过，请重新验证");
        }
    }

    private String testGenericCaptchaSetting(Map<String, String> config) {
        String url = defaultText(config.get("url"), "");
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("通用 HTTP 校验请求地址不能为空");
        }
        return "通用 HTTP 校验配置项完整。";
    }

    private void sendAliyunSms(String mobile, String code) {
        Map<String, String> config = smsLoginSetting.aliyunConfig();
        String accessKeyId = defaultText(config.get("access_key_id"), "");
        String accessKeySecret = defaultText(config.get("access_key_secret"), "");
        String signName = defaultText(config.get("sign_name"), "");
        String templateCode = defaultText(config.get("template_code"), "");
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret) || !StringUtils.hasText(signName) || !StringUtils.hasText(templateCode)) {
            throw new IllegalStateException("阿里云短信配置不完整");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("PhoneNumbers", mobile);
        params.put("SignName", signName);
        params.put("TemplateCode", templateCode);
        params.put("TemplateParam", applySmsTemplate(defaultText(config.get("template_param_json"), "{\"code\":\"{code}\"}"), mobile, code, code));
        String query = canonicalQuery(params);
        String nonce = UUID.randomUUID().toString();
        String date = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String payloadHash = sha256Hex("");
        String signedHeaders = "host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version";
        String canonicalHeaders = "host:dysmsapi.aliyuncs.com\n"
            + "x-acs-action:SendSms\n"
            + "x-acs-content-sha256:" + payloadHash + "\n"
            + "x-acs-date:" + date + "\n"
            + "x-acs-signature-nonce:" + nonce + "\n"
            + "x-acs-version:2017-05-25\n";
        String canonicalRequest = "GET\n/\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String signature = bytesToHex(hmacSha256(accessKeySecret.getBytes(StandardCharsets.UTF_8), "ACS3-HMAC-SHA256\n" + sha256Hex(canonicalRequest)));
        String authorization = "ACS3-HMAC-SHA256 Credential=" + accessKeyId + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature;
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://dysmsapi.aliyuncs.com/?" + query))
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", authorization)
            .header("x-acs-action", "SendSms")
            .header("x-acs-version", "2017-05-25")
            .header("x-acs-date", date)
            .header("x-acs-signature-nonce", nonce)
            .header("x-acs-content-sha256", payloadHash)
            .GET()
            .build();
        HttpResponse<String> response = sendHttp(request, "aliyun sms");
        if (response.statusCode() >= 400 || (!response.body().contains("\"Code\":\"OK\"") && !response.body().contains("\"Code\":\"OK\""))) {
            throw new IllegalStateException("阿里云短信返回异常：" + response.body());
        }
    }

    private HttpResponse<String> sendHttp(HttpRequest request, String action) {
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(action + " request failed: " + ex.getMessage(), ex);
        }
    }

    private String applySmsTemplate(String template, String mobile, String code, String content) {
        return defaultText(template, "")
            .replace("{mobile}", jsonEscape(mobile))
            .replace("{code}", jsonEscape(code))
            .replace("{content}", jsonEscape(content));
    }

    private String jsonEscape(String value) {
        return defaultText(value, "").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String numberJson(String value) {
        String clean = defaultText(value, "").replaceAll("[^0-9]", "");
        return StringUtils.hasText(clean) ? clean : "0";
    }

    private String normalizeCaptchaProvider(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (Set.of("TENCENT", "TURNSTILE", "GENERIC").contains(normalized)) {
            return normalized;
        }
        return "TENCENT";
    }

    private String normalizeSmsProvider(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (Set.of("GENERIC", "TENCENT", "ALIYUN").contains(normalized)) {
            return normalized;
        }
        return "TENCENT";
    }

    private Map<String, String> normalizeSmsConfig(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalizePaymentConfigKey(key);
            if (StringUtils.hasText(normalizedKey)) {
                result.put(normalizedKey, value == null ? "" : value.trim());
            }
        });
        return result;
    }

    private String tencentAuthorization(String secretId, String secretKey, String service, String method, String host, String payload, long timestamp) {
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.of("UTC")).toLocalDate().toString();
        String canonicalRequest = method + "\n/\n\ncontent-type:application/json; charset=utf-8\nhost:" + host + "\n\ncontent-type;host\n" + sha256Hex(payload);
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);
        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, service);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = bytesToHex(hmacSha256(secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope + ", SignedHeaders=content-type;host, Signature=" + signature;
    }

    private String canonicalQuery(Map<String, String> params) {
        List<String> sortedKeys = params.keySet().stream().sorted().toList();
        return sortedKeys.stream()
            .map(key -> percentEncode(key) + "=" + percentEncode(params.get(key)))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
    }

    private String percentEncode(String value) {
        return URLEncoder.encode(defaultText(value, ""), StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
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

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(defaultText(value, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private Long longValue(Object value, Long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(defaultText(value, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
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

    private void seedPaymentChannels(boolean persist) {
        if (!paymentChannels.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        paymentChannels.put(1L, new PaymentChannelItem(1L, "balance", "余额支付", "BALANCE", List.of("h5", "web", "api"), "ENABLED", 10, Map.of(), "使用用户账户余额支付", now, now));
        paymentChannels.put(2L, new PaymentChannelItem(2L, "wechat", "微信支付", "WECHAT", List.of("h5", "web"), "DISABLED", 20, Map.of(
            "app_id", "",
            "mch_id", "",
            "api_v3_key", "",
            "merchant_serial_no", "",
            "private_key", "",
            "notify_url", "",
            "sandbox", "false"
        ), "配置真实微信商户参数并完成网关下单接入后再启用", now, now));
        paymentChannels.put(3L, new PaymentChannelItem(3L, "alipay", "支付宝", "ALIPAY", List.of("h5", "web"), "DISABLED", 30, Map.of(
            "app_id", "",
            "app_private_key", "",
            "alipay_public_key", "",
            "gateway_url", "https://openapi.alipay.com/gateway.do",
            "notify_url", "",
            "sandbox", "false"
        ), "配置真实支付宝商户参数并完成网关下单接入后再启用", now, now));
        paymentChannels.put(4L, new PaymentChannelItem(4L, "bank", "线下转账", "BANK", List.of("web"), "DISABLED", 40, Map.of(
            "account_name", "",
            "bank_name", "",
            "bank_account", "",
            "qr_image_url", ""
        ), "线下转账审核通道", now, now));
        paymentChannelId.set(5L);
        if (persist) {
            persistPaymentChannels();
        }
    }

    private void seedUserGroups() {
        userGroups.put(1L, new UserGroupItem(1L, "默认会员", "注册后自动归入的基础用户组", true, 0, "ENABLED", true, false, true, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));
        userGroups.put(2L, new UserGroupItem(2L, "渠道 VIP", "仅开放私域，屏蔽人工代充类目", false, 0, "ENABLED", true, true, true, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));
        userGroups.put(3L, new UserGroupItem(3L, "受限会员", "风控观察组，限制游戏直充和淘宝平台购买", false, 0, "ENABLED", false, false, false, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));

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
