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
import com.xiyiyun.shop.persistence.FundsLedgerStore;
import com.xiyiyun.shop.persistence.MemberOrderCallbackTaskStore;
import com.xiyiyun.shop.persistence.OrderCreationStore;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
import com.xiyiyun.shop.persistence.SupplierPriceHistoryStore;
import com.xiyiyun.shop.security.LoginAttemptGuard;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryShopRepository implements TokenAuthPort {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryShopRepository.class);
    private static final Charset GBK_CHARSET = Charset.forName("GBK");
    private static final String DEFAULT_ADMIN_PASSWORD_BCRYPT = "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq";
    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);
    private static final long SLOW_ORDER_CREATION_MILLIS = 1_000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final int KASUSHOU_CATEGORY_ENRICH_LIMIT = 100;
    private static final int KASUSHOU_CATEGORY_ENRICH_MAX_PAGES = 2;
    private static final int KASUSHOU_CATEGORY_ENRICH_MAX_REQUESTS = 120;
    private static final Pattern PRICE_LIMIT_PATTERN = Pattern.compile("(?:限价|限制售价|限定价格|控价)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*(?:元|块|rmb|RMB|¥)?)");
    private static final DateTimeFormatter ORDER_NO_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter FULU_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SALES_TERMINAL_PLATFORMS = Set.of("all", "h5", "web", "pc", "api");
    private static final Set<String> LEGACY_SYSTEM_GOODS_TAGS = Set.of("new", "api-source");

    private final Map<Long, CardSecret> cards = new ConcurrentHashMap<>();
    private final Map<String, OrderItem> orders = new ConcurrentHashMap<>();
    private final Map<String, PaymentItem> payments = new ConcurrentHashMap<>();
    private final Map<Long, PaymentCallbackLogItem> paymentCallbackLogs = new ConcurrentHashMap<>();
    private final Map<String, RefundItem> refunds = new ConcurrentHashMap<>();
    private final Map<Long, SupplierItem> suppliers = new ConcurrentHashMap<>();
    private final Map<Long, String> supplierApiKeys = new ConcurrentHashMap<>();
    private final Map<Long, RemoteGoodsSyncResult> remoteGoodsSyncResults = new ConcurrentHashMap<>();
    /**
     * 批次7B / 任务B：分类锁与商品锁<b>仍在仓储声明</b>，但同一个对象引用会传给
     * {@link CatalogService}。
     *
     * <p>不能把它们搬进 CatalogService：仓储的订单侧代码（货源克隆、退款回补库存等）
     * 也在同一把 {@code goodsLock} 上同步。两边共用同一监视器，{@code synchronized} 块
     * 才继续互斥，重构前后的并发语义逐字等价。
     */
    private final Object categoryLock = new Object();
    private final Object supplierLock = new Object();
    private final Object goodsLock = new Object();
    private final Object orderLock = new Object();
    private final Object cardLock = new Object();
    private final Map<Long, PaymentChannelItem> paymentChannels = new ConcurrentHashMap<>();
    /**
     * 批次6 / 任务C：站点设置的内存默认值。
     *
     * <p>真正的持有者是 {@link ConfigService}；这里只保留一份「库里读不到时的出厂值」，
     * 构造时交给 ConfigService，之后仓储一律通过 {@code configService.systemSetting()} 读。
     */
    private static final SystemSettingItem DEFAULT_SYSTEM_SETTING = new SystemSettingItem(
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
    private final AtomicLong cardId = new AtomicLong(1);
    private final AtomicLong orderSeq = new AtomicLong(1);
    private final AtomicLong paymentSeq = new AtomicLong(1);
    private final AtomicLong paymentCallbackLogId = new AtomicLong(1);
    private final AtomicLong refundSeq = new AtomicLong(1);
    private final AtomicLong supplierId = new AtomicLong(20002);
    private final AtomicLong paymentChannelId = new AtomicLong(5);
    private final OrderEventPublisher realtimeBroadcaster;
    private final PersistentOrderStore persistentOrderStore;
    private final CatalogPersistenceStore catalogPersistenceStore;
    private final RedisSecurityStateStore securityStateStore;
    /**
     * 批次6 / 任务B：审计与日志。
     *
     * <p>仓储不再直接持有 {@link AuditPersistenceStore} —— id 分配、内存态、DB 镜像、
     * 读降级全在 {@link AuditService} 里。仓储只调 {@link #appendOperation}。
     */
    private final AuditService auditService;
    /**
     * 批次6 / 任务C：系统配置（站点设置 + KV 配置项）。
     *
     * <p>配置项的 key 与存储形状全部收在 {@link ConfigService}，为批次7 的建表迁移留出单点。
     */
    private final ConfigService configService;
    /**
     * 实体类持久化（会员组 / 组规则 —— 卡类、充值字段、供应商、商品渠道已随批次7B 交给
     * {@link CatalogService}）。
     *
     * <p>批次6 刻意没搬这些：它们是实体表 CRUD，不属于「配置」职责，塞进
     * {@link ConfigService} 只会让它变成第二个上帝类。批次7B 把其中的商品域四组
     * （卡类 / 充值字段 / 供应商 / 商品渠道）收进了 {@link CatalogService}，
     * 仓储这边只剩会员组与组规则还在直接用。
     */
    private final ConfigPersistenceStore configPersistenceStore;
    /** 批次6 / 任务A：商品监控。 */
    private final ProductMonitorService productMonitorService;
    /**
     * 批次7B / 任务B：商品目录域（商品 / 分类 / 卡类 / 商品渠道 / 充值字段 / 价格模板）。
     *
     * <p>这五组实体的内存 Map、id 序列、DB 镜像、读降级、字段归一化全在
     * {@link CatalogService}。仓储自己不再持有 {@code goods} / {@code categories} /
     * {@code cardKinds} / {@code goodsChannels} / {@code rechargeFields} 任何一张表。
     */
    private final CatalogService catalogService;
    /**
     * 批次7C / 任务C1：会员域（会员 / 会员组 / 组规则 / 会员 API 凭据）。
     *
     * <p>这四组实体的内存 Map、id 序列、DB 镜像、组规则匹配、开放接口签名校验全在
     * {@link UserService}。仓储自己不再持有 {@code users} / {@code userGroups} /
     * {@code groupRules} / {@code memberCredentials} 任何一张表。
     *
     * <p><b>资金铁律</b>：会员域只<b>调用</b> {@link com.xiyiyun.shop.persistence.FundsLedgerStore}
     * 的 {@code creditByAdmin} / {@code debitByAdmin}，批次4 的条件 UPDATE + 受影响行数判定 +
     * {@code user_balance_transactions} 幂等流水一行都没有复制进去。
     */
    private final UserService userService;
    /**
     * 批次8D / 任务D1：鉴权域（会员登录 / 管理端登录 / 令牌 / 口令 / 短信验证码 /
     * 人机验证 / 员工账号）的唯一出口。
     *
     * <p>仓储不再持有 {@code userTokens} / {@code adminTokens} / {@code userPasswordHashes} /
     * {@code adminStaff} / {@code smsVerificationCodes} / {@code smsLoginSetting} /
     * {@code captchaSetting} 任何一张表或任何一份设置，下面那些登录域公开方法全部退化为转发壳。
     *
     * <p>批次5 / B2 的四条登录加固红线（账号+IP 双维度阶梯锁定、统一失败文案、
     * BCrypt 时间侧信道补偿、「员工已停用」必须在口令校验通过后才提示）整块由
     * {@link AuthService} 承接，调用顺序逐字未动。
     */
    private final AuthService authService;

    @Autowired(required = false)
    private AltchaCaptchaService altchaCaptchaService;
    /**
     * 批次4：资金 / 库存 / 订单状态的原子操作出口。
     *
     * <p>用<b>字段注入</b>而非构造器参数，有两个硬理由：
     * <ol>
     *   <li>{@code @Transactional} 靠 Spring 代理生效，<b>同类内部自调用会绕过代理</b>。
     *       事务方法必须住在另一个 bean 里，由本类作为外部调用方进入，事务边界才真实存在。</li>
     *   <li>现有 4 个测试类（RepositoryProductionPersistenceTest / PersistentOrderStoreTest /
     *       CatalogPersistenceStoreTest / BackendCoreFixesTest）直接 new 本类的构造器，
     *       加参数会把原本绿的用例全部编译失败。</li>
     * </ol>
     * 为 null 时（纯内存单测）退化到内存路径。
     */
    @Autowired(required = false)
    private FundsLedgerStore fundsLedgerStore;
    @Autowired(required = false)
    private OrderCreationStore orderCreationStore;
    @Autowired(required = false)
    @Qualifier("applicationTaskExecutor")
    private TaskExecutor orderEventExecutor;
    @Autowired(required = false)
    private MemberOrderCallbackTaskStore memberOrderCallbackTaskStore;
    /**
     * 批次5 / B2：登录失败计数 + 阶梯锁定 + 同 IP 频率限制。
     *
     * <p>与 {@link #fundsLedgerStore} 同理走<b>字段注入</b>：现有 4 个测试类直接 new 本类构造器，
     * 加构造参数会把原本绿的用例全部编译失败。为 null 时（纯内存单测）退化为
     * {@code AuthService.loginGuard()} 里惰性创建的进程内实例，防护逻辑依旧生效。
     *
     * <p>批次8D / 任务D1：字段留在仓储，但读取方一律经 {@code RepositoryAuthGateway}
     * <b>每次现取</b>——{@link #replaceLoginAttemptGuardForTest} 会在运行期整个换掉实现，
     * 缓存一份就等于把测试注入的阶梯参数丢掉。
     */
    @Autowired(required = false)
    private LoginAttemptGuard loginAttemptGuard;
    /**
     * 批次5 / B2：管理端登录强制人机验证。
     *
     * <p>为 true 时，只要人机验证「已配置好凭据」，管理端登录一律要求验证码，
     * 不再受 {@code captchaSetting.enabled()} / {@code adminLoginEnabled()} 两个后台开关影响
     * （原实现两个开关默认 false，等于管理端登录裸奔）。
     * 走字段注入的原因同 {@link #loginAttemptGuard}。
     */
    @Value("${xiyiyun.login.admin-captcha-required:true}")
    private boolean adminCaptchaRequired = true;
    /** 批次3：替换 6 处 platformType 分发链的唯一落点。 */
    private final SupplierAdapterRegistry supplierAdapters = SupplierAdapterRegistry.defaultRegistry();
    /**
     * 批次3：统一连接池 / 超时 / 重试；供应商差异由 SupplierHttpProfile 声明。
     * 非 final 仅为便于测试注入桩（见 {@link #replaceSupplierHttpClientForTest}）。
     */
    private SupplierHttpClient supplierHttp = new SupplierHttpClient();
    private final boolean prodProfile;
    private volatile String adminUsername;
    private volatile String adminPasswordBcrypt;
    private volatile String adminNickname;

    public InMemoryShopRepository(
        OrderEventPublisher realtimeBroadcaster,
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
            (SupplierPriceHistoryStore) null,
            false,
            adminUsername,
            adminPasswordBcrypt,
            adminNickname
        );
    }

    public InMemoryShopRepository(
        OrderEventPublisher realtimeBroadcaster,
        ObjectProvider<PersistentOrderStore> persistentOrderStoreProvider,
        ObjectProvider<CatalogPersistenceStore> catalogPersistenceStoreProvider,
        ObjectProvider<AuditPersistenceStore> auditPersistenceStoreProvider,
        ObjectProvider<ConfigPersistenceStore> configPersistenceStoreProvider,
        ObjectProvider<RedisSecurityStateStore> securityStateStoreProvider,
        Environment environment,
        String adminUsername,
        String adminPasswordBcrypt,
        String adminNickname
    ) {
        this(
            realtimeBroadcaster,
            persistentOrderStoreProvider.getIfAvailable(),
            catalogPersistenceStoreProvider.getIfAvailable(),
            auditPersistenceStoreProvider.getIfAvailable(),
            configPersistenceStoreProvider.getIfAvailable(),
            securityStateStoreProvider.getIfAvailable(),
            null,
            isProdProfile(environment),
            adminUsername,
            adminPasswordBcrypt,
            adminNickname
        );
    }

    @Autowired
    public InMemoryShopRepository(
        OrderEventPublisher realtimeBroadcaster,
        ObjectProvider<PersistentOrderStore> persistentOrderStoreProvider,
        ObjectProvider<CatalogPersistenceStore> catalogPersistenceStoreProvider,
        ObjectProvider<AuditPersistenceStore> auditPersistenceStoreProvider,
        ObjectProvider<ConfigPersistenceStore> configPersistenceStoreProvider,
        ObjectProvider<RedisSecurityStateStore> securityStateStoreProvider,
        ObjectProvider<SupplierPriceHistoryStore> supplierPriceHistoryStoreProvider,
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
            supplierPriceHistoryStoreProvider.getIfAvailable(),
            isProdProfile(environment),
            adminUsername,
            adminPasswordBcrypt,
            adminNickname
        );
    }

    private InMemoryShopRepository(
        OrderEventPublisher realtimeBroadcaster,
        PersistentOrderStore persistentOrderStore,
        CatalogPersistenceStore catalogPersistenceStore,
        AuditPersistenceStore auditPersistenceStore,
        ConfigPersistenceStore configPersistenceStore,
        RedisSecurityStateStore securityStateStore,
        SupplierPriceHistoryStore supplierPriceHistoryStore,
        boolean prodProfile,
        String adminUsername,
        String adminPasswordBcrypt,
        String adminNickname
    ) {
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.persistentOrderStore = persistentOrderStore;
        this.catalogPersistenceStore = catalogPersistenceStore;
        this.securityStateStore = securityStateStore;
        this.configPersistenceStore = configPersistenceStore;
        // 顺序有讲究：ConfigService 落库失败时要写审计，所以 AuditService 必须先就位。
        this.auditService = new AuditService(auditPersistenceStore);
        this.configService = new ConfigService(
            configPersistenceStore,
            this.auditService,
            DEFAULT_SYSTEM_SETTING,
            this::normalizeRegistrationType,
            this::validDefaultUserGroupId
        );
        this.productMonitorService = new ProductMonitorService(
            new RepositoryMonitorGateway(),
            realtimeBroadcaster,
            this.configService,
            supplierPriceHistoryStore
        );
        // 批次7B / 任务B：商品域。锁传的是仓储自己那两个监视器对象，两边继续互斥。
        this.catalogService = new CatalogService(
            new RepositoryCatalogGateway(),
            this.auditService,
            this.configService,
            catalogPersistenceStore,
            configPersistenceStore,
            this.goodsLock,
            this.categoryLock
        );
        // 批次7C / 任务C1：会员域。CatalogService 先构造没问题 —— 它对会员域的调用
        // 全走 RepositoryCatalogGateway，都是运行期才转到 userService 的晚绑定。
        this.userService = new UserService(
            new RepositoryUserGateway(),
            this.auditService,
            this.configService,
            catalogPersistenceStore,
            configPersistenceStore,
            securityStateStore
        );
        this.prodProfile = prodProfile;
        this.adminUsername = normalize(defaultText(adminUsername, "admin"));
        this.adminPasswordBcrypt = defaultText(adminPasswordBcrypt, DEFAULT_ADMIN_PASSWORD_BCRYPT);
        this.adminNickname = defaultText(adminNickname, "运营管理员");
        // 批次8D / 任务D1：鉴权域。必须排在上面三行归一化之后 —— 超级管理员的出厂账号 /
        // 口令哈希 / 昵称要以「归一后」的值交给 AuthService，它自此成为唯一持有者。
        this.authService = new AuthService(
            new RepositoryAuthGateway(),
            this.auditService,
            this.configService,
            configPersistenceStore,
            securityStateStore,
            new AltchaCaptchaService(),
            this.adminUsername,
            this.adminPasswordBcrypt,
            this.adminNickname
        );
        configService.loadPersistentSystemSetting();
        // 四份登录域 DB 镜像的读回也随实现搬到了 AuthService，这里只保留调用次序。
        authService.loadSuperAdminCredentials();
        authService.loadSmsLoginSetting();
        authService.loadCaptchaSetting();
        authService.loadAdminStaff();
        loadPaymentChannels();
        loadPriceTemplates();
        // 批次6 / 任务A：把已落库的扫描计划读回，避免重启后所有渠道同时打上游。
        productMonitorService.loadPersistedStates();
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
            || auditService.persistenceEnabled()
            || configPersistenceStore != null;
    }

    /**
     * 资金 / 库存是否走 DB 原子路径。
     *
     * <p>要求同时具备订单持久化与流水 store：两者缺一，条件 UPDATE 就没有可写的真实数据行。
     */
    private boolean fundsLedgerEnabled() {
        return fundsLedgerStore != null && persistentOrderStore != null;
    }

    private boolean persistentOrderCreationEnabled() {
        return orderCreationStore != null && persistentOrderStore != null;
    }

    /** 仅供测试注入资金 store（生产由 Spring 注入）。 */
    void replaceFundsLedgerStoreForTest(FundsLedgerStore store) {
        this.fundsLedgerStore = store;
    }

    private boolean fullPersistenceEnabled() {
        return persistentOrderStore != null
            && catalogPersistenceStore != null
            && auditService.persistenceEnabled()
            && configPersistenceStore != null;
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null
            && List.of(environment.getActiveProfiles()).stream().anyMatch("prod"::equalsIgnoreCase);
    }


















    public SystemSettingItem systemSetting() {
        return configService.systemSetting();
    }

    // ================================================================
    // 批次8D / 任务D1：鉴权域的转发壳
    //
    // 下面这些方法的<b>实现已整块搬到 {@link AuthService}</b>，这里只留同名同签名的
    // 转发。理由与批次6/7B/7C 的转发壳一致：AdminMvpController / H5MvpController /
    // MemberMvpController 上有几十处调用点，顺手改成 {@code authService.xxx()}
    // 只会在一次纯结构重构里制造巨大 diff，掩盖真正的搬迁动作。
    //
    // {@code synchronized} 修饰符逐字保留：仓储自身仍有非登录代码与这些方法竞争同一个
    // 实例锁（如订单侧读会员快照），去掉会悄悄放宽并发语义。

    public SmsLoginSettingItem smsLoginSetting() {
        return authService.smsLoginSetting();
    }

    public CaptchaSettingItem captchaSetting() {
        return authService.captchaSetting();
    }

    public CaptchaChallengeItem captchaChallenge(String terminal) {
        return authService.captchaChallenge(terminal);
    }

    public String altchaChallenge() {
        AltchaCaptchaService service = altchaCaptchaService;
        if (service == null) {
            service = new AltchaCaptchaService();
        }
        return authService.altchaChallenge();
    }

    public synchronized SmsLoginSettingItem updateSmsLoginSetting(SmsLoginSettingRequest request) {
        return authService.updateSmsLoginSetting(request);
    }

    public synchronized CaptchaSettingItem updateCaptchaSetting(CaptchaSettingRequest request) {
        return authService.updateCaptchaSetting(request);
    }

    public String testCaptchaSetting(CaptchaSettingRequest request) {
        return authService.testCaptchaSetting(request);
    }

    public synchronized String sendAdminLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
        return authService.sendAdminLoginSmsCode(request, clientIp);
    }

    public synchronized String sendUserLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
        return authService.sendUserLoginSmsCode(request, clientIp);
    }

    public synchronized String createSliderToken(String terminal) {
        return authService.createSliderToken(terminal);
    }

    public SystemSettingItem updateSystemSetting(UpdateSystemSettingRequest request) {
        return configService.updateSystemSetting(request);
    }



    public synchronized AuthSession<UserItem> loginUser(LoginRequest request) {
        return loginUser(request, "");
    }

    public synchronized AuthSession<UserItem> loginUser(LoginRequest request, String clientIp) {
        // 第三参 verifyCaptcha=true 与重构前的私有三参重载逐字一致：
        // 只有 authenticateUser 的 login 分支会传 false（它自己已先校验过人机验证）。
        return authService.loginUser(request, clientIp, true);
    }

    /**
     * 仅供测试注入自定义节流参数。
     *
     * <p>批次8D / 任务D1 后仍写<b>仓储自己的</b> {@code loginAttemptGuard} 字段：
     * {@code RepositoryAuthGateway.loginAttemptGuard()} 每次现取，替换会自动透传到
     * {@link AuthService}，{@code LoginBruteForceProtectionTest} 注入的阶梯参数才生效。
     */
    void replaceLoginAttemptGuardForTest(LoginAttemptGuard guard) {
        this.loginAttemptGuard = guard;
    }

    public synchronized AuthSession<UserItem> authenticateUser(UserAuthRequest request, String clientIp) {
        return authService.authenticateUser(request, clientIp);
    }

    public synchronized UserItem changeUserPassword(Long userId, PasswordChangeRequest request) {
        return authService.changeUserPassword(userId, request);
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
            user.verificationStatus(),
            user.username()
        );
        userService.usersMap().put(userId, next);
        persistUserSnapshot(next);
        String requestNo = "RCH" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", paymentSeq.getAndIncrement());
        appendOperation("USER_RECHARGE_SUCCESS", "USER", String.valueOf(userId), method + ":" + amount.toPlainString() + ":" + defaultText(request == null ? "" : request.remark(), ""));
        return new RechargeRequestResult(requestNo, amount, method, "SUCCESS", now, withGroupName(next));
    }

    public Optional<UserItem> findUserByToken(String token) {
        return authService.findUserByToken(token);
    }

    public void logoutUser(String token) {
        authService.logoutUser(token);
    }

    public AuthSession<AdminProfile> loginAdmin(LoginRequest request, String clientIp) {
        return authService.loginAdmin(request, clientIp);
    }

    public Optional<AdminProfile> findAdminByToken(String token) {
        return authService.findAdminByToken(token);
    }

    public void logoutAdmin(String token) {
        authService.logoutAdmin(token);
    }

    public synchronized AdminProfile updateSuperAdminCredentials(String token, AdminCredentialRequest request) {
        return authService.updateSuperAdminCredentials(token, request);
    }

    public synchronized List<AdminStaffItem> listAdminStaff() {
        return authService.listAdminStaff();
    }

    public synchronized AdminStaffItem createAdminStaff(AdminStaffRequest request) {
        return authService.createAdminStaff(request);
    }

    public synchronized AdminStaffItem updateAdminStaff(Long id, AdminStaffRequest request) {
        return authService.updateAdminStaff(id, request);
    }

    public synchronized void deleteAdminStaff(Long id) {
        authService.deleteAdminStaff(id);
    }

    // ------------------------------------------------------------------ 批次7C / 任务C1：会员域转发壳
    //
    // 下面这些方法的<b>实现已整块搬到 {@link UserService}</b>，这里只留同名同签名的
    // 转发壳。保留转发壳而不是让控制器直连新服务，是因为 AdminMvpController /
    // H5MvpController / MemberMvpController 上有几十处调用点，同时改
    // {@code userService.xxx()} 只会在一次纯结构重构里制造巨大 diff，
    // 掩盖真正的搬迁动作。批次8 拆完 OrderService 后再统一把控制器切到新服务。

    public List<UserGroupItem> listUserGroups() {
        return userService.listUserGroups();
    }

    /** 批次8C：会员分页。降级判断在 {@code UserService} 内部，这里只做透传。 */
    public PageSlice<UserItem> pageUsers(int limit, long offset) {
        return userService.pageUsers(limit, offset);
    }

    Optional<UserItem> findOutboundUser(Long userId) {
        return userService.findUserSnapshot(userId);
    }

    public synchronized UserGroupItem createUserGroup(CreateUserGroupRequest request) {
        return userService.createUserGroup(request);
    }

    public synchronized UserGroupItem updateUserGroupOrderPermission(Long groupId, UpdateUserGroupOrderPermissionRequest request) {
        return userService.updateUserGroupOrderPermission(groupId, request);
    }

    public synchronized List<GroupRuleItem> updateGroupRules(Long groupId, UpdateGroupRulesRequest request) {
        return userService.updateGroupRules(groupId, request);
    }

    public synchronized UserItem updateUserGroup(Long userId, UpdateUserGroupRequest request) {
        return userService.updateUserGroup(userId, request);
    }

    public synchronized UserItem adminCreateUser(AdminCreateUserRequest request) {
        return userService.adminCreateUser(request);
    }

    public synchronized UserItem updateUserCredentials(Long userId, AdminUserCredentialRequest request) {
        return userService.updateUserCredentials(userId, request);
    }

    public UserItem adjustUserFunds(Long userId, UserFundAdjustRequest request) {
        return userService.adjustUserFunds(userId, request);
    }

    public List<MemberApiCredentialItem> listMemberCredentials() {
        return userService.listMemberCredentials();
    }

    public synchronized MemberApiCredentialItem memberCredentialForUser(Long userId) {
        return userService.memberCredentialForUser(userId);
    }

    public synchronized MemberApiCredentialItem saveMemberCredential(Long userId, MemberApiCredentialRequest request) {
        MemberApiCredentialItem previous = userService.memberCredentialForUser(userId);
        MemberApiCredentialItem saved = userService.saveMemberCredential(userId, request);
        if (memberOrderCallbackTaskStore != null
            && !Objects.equals(defaultText(previous.callbackUrl(), "").trim(), defaultText(saved.callbackUrl(), "").trim())) {
            memberOrderCallbackTaskStore.cancelPendingForUserExceptUrl(
                userId, saved.callbackUrl(), "callbackUrl was changed or cleared"
            );
        }
        return saved;
    }

    public UserItem authenticateMemberApi(String appKey, String timestamp, String nonce, String signature, String path, String clientIp) {
        return userService.authenticateMemberApi(appKey, timestamp, nonce, signature, path, clientIp);
    }

    public UserItem authenticateMemberApi(
        String appKey,
        String timestamp,
        String nonce,
        String signature,
        String path,
        String clientIp,
        String contentHash
    ) {
        return userService.authenticateMemberApi(appKey, timestamp, nonce, signature, path, clientIp, contentHash);
    }

    OutboundApiPrincipal prepareOutboundCredential(String appKey, String path, String clientIp) {
        return userService.prepareOutboundCredential(appKey, path, clientIp);
    }

    void completeOutboundCredential(OutboundApiPrincipal principal, String path) {
        userService.completeOutboundCredential(principal, path);
    }

    void rejectOutboundCredential(OutboundApiPrincipal principal, String appKey, String path, String message) {
        userService.rejectOutboundCredential(principal, appKey, path, message);
    }

    String outboundProtocolSettingsJson() {
        return configService.outboundProtocolSettingsJson();
    }

    void saveOutboundProtocolSettingsJson(String json) {
        configService.saveOutboundProtocolSettingsJson(json);
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











    public ProductMonitorOverview productMonitorOverview() {
        return productMonitorOverview(1, 10);
    }

    public ProductMonitorOverview productMonitorOverview(Integer page, Integer pageSize) {
        return productMonitorService.overview(page, pageSize);
    }

    public List<ProductMonitorLogItem> listProductMonitorLogs() {
        return productMonitorService.listLogs();
    }

    public List<Long> dueProductMonitorChannelIds(OffsetDateTime now) {
        return productMonitorService.dueChannelIds(now);
    }

    public List<ProductMonitorScanResult> scanAllProductMonitorChannels(boolean manual) {
        return productMonitorService.scanAll(manual);
    }

    public ProductMonitorScanResult scanProductMonitorChannel(Long channelId, boolean manual) {
        return productMonitorService.scanChannel(channelId, manual);
    }


    /**
     * {@link ProductMonitorGateway} 的实现：把监控要用的仓储私有能力开放给
     * {@link ProductMonitorService}，且<b>仅开放这几个</b>。
     *
     * <p>用内部类而不是让仓储自己 {@code implements ProductMonitorGateway}：
     * 后者会迫使 {@code isProductMonitorChannel} / {@code monitoredRemoteGoods} 等
     * 6 个私有方法升级为 public，等于给一万行的仓储再加 6 个对外 API。
     */
    private final class RepositoryMonitorGateway implements ProductMonitorGateway {
        @Override
        public List<GoodsChannelItem> allGoodsChannelSnapshots() {
            return InMemoryShopRepository.this.allGoodsChannelSnapshots();
        }

        @Override
        public Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long channelId) {
            return InMemoryShopRepository.this.findGoodsChannelSnapshot(channelId);
        }

        @Override
        public Optional<GoodsItem> findGoodsSnapshot(Long goodsId) {
            return InMemoryShopRepository.this.findGoodsSnapshot(goodsId);
        }

        @Override
        public List<GoodsItem> allGoodsSnapshots() {
            return InMemoryShopRepository.this.allGoodsSnapshots();
        }

        @Override
        public boolean isProductMonitorChannel(GoodsChannelItem channel) {
            return InMemoryShopRepository.this.isProductMonitorChannel(channel);
        }

        @Override
        public boolean isProductMonitorChannel(GoodsChannelItem channel, Map<Long, GoodsItem> goodsById) {
            return InMemoryShopRepository.this.isProductMonitorChannel(channel, goodsById);
        }

        @Override
        public SupplierItem requiredSupplier(Long supplierId) {
            return InMemoryShopRepository.this.requiredSupplier(supplierId);
        }

        @Override
        public MonitoredRemoteGoods monitoredRemoteGoods(GoodsItem current, GoodsChannelItem channel, SupplierItem supplier) {
            return InMemoryShopRepository.this.monitoredRemoteGoods(current, channel, supplier);
        }

        @Override
        public GoodsItem applyMonitoredRemoteGoods(GoodsItem current, MonitoredRemoteGoods remote, List<String> changes) {
            return InMemoryShopRepository.this.applyMonitoredRemoteGoods(current, remote, changes);
        }

        @Override
        public void applyMonitoredGoodsUpdate(GoodsItem next) {
            InMemoryShopRepository.this.applyMonitoredGoodsUpdate(next);
        }
    }

    /**
     * 批次7B / 任务B：{@link CatalogGateway} 的实现 —— 商品域反向要的仓储能力，仅此几项。
     *
     * <p>与 {@link RepositoryMonitorGateway} 同理用内部类：否则
     * {@code availableCardCount} / {@code rulesForGroup} 等私有方法都得升成 public。
     */
    private final class RepositoryCatalogGateway implements CatalogGateway {
        @Override
        public int availableCardCount(Long goodsId) {
            return InMemoryShopRepository.this.availableCardCount(goodsId);
        }

        @Override
        public int availableCardKindCardCount(Long cardKindId) {
            return InMemoryShopRepository.this.availableCardKindCardCount(cardKindId);
        }

        @Override
        public int cardKindTotalCount(Long cardKindId) {
            return (int) cards.values().stream()
                .filter(card -> Objects.equals(card.cardKindId(), cardKindId))
                .count();
        }

        @Override
        public int cardKindUsedCount(Long cardKindId) {
            return (int) cards.values().stream()
                .filter(card -> Objects.equals(card.cardKindId(), cardKindId))
                .filter(card -> "USED".equals(card.status()))
                .count();
        }

        @Override
        public void removeCardsForGoods(Long goodsId) {
            cards.entrySet().removeIf(entry -> Objects.equals(entry.getValue().goodsId(), goodsId));
        }

        @Override
        public void deletePersistentCardsByGoods(Long goodsId) {
            InMemoryShopRepository.this.deletePersistentCardsByGoods(goodsId);
        }

        @Override
        public boolean fundsLedgerEnabled() {
            return InMemoryShopRepository.this.fundsLedgerEnabled();
        }

        @Override
        public void syncCardGoodsStock(Long goodsId, Long cardKindId) {
            fundsLedgerStore.syncCardGoodsStock(goodsId, cardKindId);
        }

        // 批次7C / 任务C1：这三项已经搬进 UserService，这里直接转过去，
        // 不在仓储里再留一层同名私有壳（商品域是唯一调用方）。
        @Override
        public Optional<UserGroupItem> findUserGroupSnapshot(Long groupId) {
            return userService.findUserGroupSnapshot(groupId);
        }

        @Override
        public List<GroupRuleItem> rulesForGroup(Long groupId) {
            return userService.rulesForGroup(groupId);
        }

        @Override
        public boolean allowedByGroupRules(GoodsItem item, List<GroupRuleItem> rules) {
            return userService.allowedByGroupRules(item, rules);
        }

        @Override
        public SupplierItem requiredSupplier(Long supplierId) {
            return InMemoryShopRepository.this.requiredSupplier(supplierId);
        }

        @Override
        public Optional<SupplierItem> supplierSnapshot(Long supplierId) {
            return Optional.ofNullable(suppliers.get(supplierId));
        }

        @Override
        public Optional<GoodsIntegrationItem> cachedRemoteIntegration(SupplierItem supplier, String supplierGoodsId) {
            return Optional.ofNullable(remoteGoodsSyncResults.get(supplier.id()))
                .flatMap(result -> exactRemoteGoods(result.items(), supplierGoodsId))
                .map(remote -> remoteGoodsIntegration(supplier, remote));
        }

        @Override
        public void forgetMonitorChannel(Long channelId) {
            productMonitorService.forgetChannel(channelId);
        }

        @Override
        public void forgetMonitorChannelWithLogs(Long channelId) {
            productMonitorService.forgetChannelWithLogs(channelId);
        }
    }

    /**
     * 批次7C / 任务C1：{@link UserGateway} 的实现 —— 会员域反向要的仓储能力，仅此几项。
     *
     * <p>同样用内部类：否则 {@code fundsLedgerEnabled} / {@code categoryTreeIds} /
     * {@code invalidateUserTokens} 等私有方法都得升成 public。
     */
    private final class RepositoryUserGateway implements UserGateway {
        @Override
        public boolean fundsLedgerEnabled() {
            return InMemoryShopRepository.this.fundsLedgerEnabled();
        }

        /**
         * 现取而不缓存：{@code fundsLedgerStore} 是 {@code @Autowired(required = false)}
         * 字段注入，测试还会用 {@code replaceFundsLedgerStoreForTest} 换实现。
         */
        @Override
        public FundsLedgerStore fundsLedger() {
            return fundsLedgerStore;
        }

        /**
         * 原先内联在这里的四步（编码口令 / 写哈希表 / 落库 / 清令牌）在批次8D / 任务D1
         * 随口令哈希表与令牌表一起归 {@link AuthService}，这里只剩转发。
         */
        @Override
        public void resetUserPassword(Long userId, String newPassword) {
            authService.resetUserPassword(userId, newPassword);
        }

        @Override
        public void validateNewPassword(String password, String confirmPassword) {
            authService.validateNewPassword(password, confirmPassword);
        }

        @Override
        public void invalidateUserTokens(Long userId) {
            authService.invalidateUserTokens(userId);
        }

        @Override
        public String normalizeRegistrationType(String value) {
            return InMemoryShopRepository.this.normalizeRegistrationType(value);
        }

        @Override
        public Set<Long> categoryTreeIds(Long rootId) {
            return InMemoryShopRepository.this.categoryTreeIds(rootId);
        }

        @Override
        public Optional<CategoryItem> findCategorySnapshot(Long id) {
            return InMemoryShopRepository.this.findCategorySnapshot(id);
        }

        @Override
        public List<String> normalizeTextList(List<String> values) {
            return InMemoryShopRepository.this.normalizeTextList(values);
        }
    }

    /**
     * 批次8D / 任务D1：{@link AuthGateway} 的实现 —— 鉴权域反向要的仓储能力，仅此 30 项。
     *
     * <p>用内部类而非把仓储整个传进 {@link AuthService}：否则 {@code sendTencentSms} /
     * {@code putEncryptedConfig} / {@code requiredUser} 等二十多个私有方法都得升成 public，
     * 等于把「登录域还欠仓储什么」这件事重新藏起来。
     */
    private final class RepositoryAuthGateway implements AuthGateway {

        /**
         * 现取而不缓存：{@code loginAttemptGuard} 是 {@code @Autowired(required = false)}
         * 字段注入，{@link #replaceLoginAttemptGuardForTest} 还会在运行期换实现
         * （{@code LoginBruteForceProtectionTest} 靠它注入阶梯参数）。
         * 可能为 null，兜底实例由 {@code AuthService.loginGuard()} 负责。
         */
        @Override
        public LoginAttemptGuard loginAttemptGuard() {
            return loginAttemptGuard;
        }

        /** 同样现取不缓存：{@code adminCaptchaRequired} 是 {@code @Value} 字段注入。 */
        @Override
        public boolean adminCaptchaRequired() {
            return adminCaptchaRequired;
        }

        // ---- 短信 / 人机验证的出网传输层：签名工具与 HttpClient 封装留在仓储 ----

        @Override
        public void sendGenericSms(Map<String, String> config, String mobile, String code, String content) {
            InMemoryShopRepository.this.sendGenericSms(config, mobile, code, content);
        }

        @Override
        public void sendTencentSms(Map<String, String> config, String mobile, String code) {
            InMemoryShopRepository.this.sendTencentSms(config, mobile, code);
        }

        @Override
        public void sendAliyunSms(Map<String, String> config, String mobile, String code) {
            InMemoryShopRepository.this.sendAliyunSms(config, mobile, code);
        }

        @Override
        public void verifyTencentCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp) {
            InMemoryShopRepository.this.verifyTencentCaptcha(config, ticket, randstr, clientIp);
        }

        @Override
        public void verifyTurnstileCaptcha(Map<String, String> config, String token, String clientIp) {
            InMemoryShopRepository.this.verifyTurnstileCaptcha(config, token, clientIp);
        }

        @Override
        public void verifyGenericCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp) {
            InMemoryShopRepository.this.verifyGenericCaptcha(config, ticket, randstr, clientIp);
        }

        @Override
        public String testTencentCaptchaSetting(Map<String, String> config) {
            return InMemoryShopRepository.this.testTencentCaptchaSetting(config);
        }

        @Override
        public String testTurnstileCaptchaSetting(Map<String, String> config) {
            return InMemoryShopRepository.this.testTurnstileCaptchaSetting(config);
        }

        @Override
        public String testGenericCaptchaSetting(Map<String, String> config) {
            return InMemoryShopRepository.this.testGenericCaptchaSetting(config);
        }

        // ---- 配置密文编解码与文本归一：敏感键判定被支付渠道等非登录代码共用 ----

        @Override
        public void putEncryptedConfig(Map<String, Object> payload, String key, Map<String, String> config) {
            InMemoryShopRepository.this.putEncryptedConfig(payload, key, config);
        }

        @Override
        public Map<String, String> settingConfig(Map<String, Object> payload, String key) {
            return InMemoryShopRepository.this.settingConfig(payload, key);
        }

        @Override
        public Map<String, String> normalizeSmsConfig(Map<String, String> config) {
            return InMemoryShopRepository.this.normalizeSmsConfig(config);
        }

        @Override
        public String normalizeTerminal(String value) {
            return InMemoryShopRepository.this.normalizeTerminal(value);
        }

        @Override
        public String normalizeRegistrationType(String value) {
            return InMemoryShopRepository.this.normalizeRegistrationType(value);
        }

        @Override
        public List<String> normalizeTextList(List<String> values) {
            return InMemoryShopRepository.this.normalizeTextList(values);
        }

        @Override
        public OffsetDateTime parseOffsetDateTime(String value) {
            return InMemoryShopRepository.this.parseOffsetDateTime(value);
        }

        @Override
        public List<String> stringList(Object value) {
            return InMemoryShopRepository.this.stringList(value);
        }

        // ---- 审计与读降级：动作名与日志文案是运维锚点，不在鉴权域重写一份 ----

        @Override
        public void recordReadFallback(String targetType, String targetId, Exception ex) {
            InMemoryShopRepository.this.recordReadFallback(targetType, targetId, ex);
        }

        @Override
        public String persistenceErrorMessage(Exception ex) {
            return InMemoryShopRepository.this.persistenceErrorMessage(ex);
        }

        // ---- 会员域读写：批次7C 已归 UserService，登录流程经仓储转发 ----

        @Override
        public List<UserItem> allUserSnapshots() {
            return InMemoryShopRepository.this.allUserSnapshots();
        }

        @Override
        public Optional<UserItem> findUserSnapshot(Long id) {
            return InMemoryShopRepository.this.findUserSnapshot(id);
        }

        @Override
        public UserItem requiredUser(Long id) {
            return InMemoryShopRepository.this.requiredUser(id);
        }

        @Override
        public UserItem withGroupName(UserItem user) {
            return InMemoryShopRepository.this.withGroupName(user);
        }

        @Override
        public UserItem withUserLastLoginAt(UserItem user, OffsetDateTime lastLoginAt) {
            return InMemoryShopRepository.this.withUserLastLoginAt(user, lastLoginAt);
        }

        @Override
        public UserItem createUserFromAccount(String account, String username) {
            return InMemoryShopRepository.this.createUserFromAccount(account, username);
        }

        /** 写回会员内存表：全仓 11 处都是这个写法，共用 {@code UserService.usersMap()} 引用。 */
        @Override
        public void putUser(UserItem user) {
            userService.usersMap().put(user.id(), user);
        }

        @Override
        public void persistUserSnapshot(UserItem user) {
            InMemoryShopRepository.this.persistUserSnapshot(user);
        }

        @Override
        public void validateRegistration(String account) {
            InMemoryShopRepository.this.validateRegistration(account);
        }
    }

    // ================================================================
    // 批次7B / 任务B：商品域的转发壳
    //
    // 下面这些方法的<b>实现已整块搬到 {@link CatalogService}</b>，这里只留同名同签名的
    // 转发。刻意这么做的理由和批次6 的 appendOperation 一样：仓储内部（下单、支付、
    // 交付、退款、上游对接、商品监控）对它们的调用点有上百处，全部改成
    // {@code catalogService.xxx()} 只会在一次纯结构重构里制造巨大 diff，
    // 却不带来任何职责上的收益 —— 职责已经搬走了，调用点零改动。
    //
    // 其中 public 的那几个（listCategories / createGoods / listGoodsChannels ...）
    // 同时也是控制器与现存测试直接调用的 API，签名必须原样保留。
    // ================================================================

    public List<CategoryItem> listCategories() {
        return catalogService.listCategories();
    }

    public List<CardKindItem> listCardKinds() {
        return catalogService.listCardKinds();
    }

    public synchronized CardKindItem createCardKind(CreateCardKindRequest request) {
        return catalogService.createCardKind(request);
    }

    public CategoryItem createCategory(CreateCategoryRequest request) {
        return catalogService.createCategory(request);
    }

    public CategoryItem updateCategory(Long id, UpdateCategoryRequest request) {
        return catalogService.updateCategory(id, request);
    }

    public List<CategoryItem> reorderCategories(ReorderCategoriesRequest request) {
        return catalogService.reorderCategories(request);
    }

    public CategoryItem updateCategoryStatus(Long id, boolean enabled) {
        return catalogService.updateCategoryStatus(id, enabled);
    }

    public void deleteCategory(Long id) {
        catalogService.deleteCategory(id);
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, boolean admin) {
        return catalogService.listGoods(categoryId, search, platform, admin);
    }

    public List<GoodsListItem> listPublicGoods(Long categoryId, String search, String platform, Long userGroupId) {
        return catalogService.listPublicGoods(categoryId, search, platform, userGroupId);
    }

    public PageResult<GoodsListItem> pagePublicGoods(Long categoryId, String search, String platform, Long userGroupId, int page, int pageSize) {
        return catalogService.pagePublicGoods(categoryId, search, platform, userGroupId, page, pageSize);
    }

    public List<GoodsListItem> listAdminGoods(Long categoryId, String search, String platform) {
        return catalogService.listAdminGoods(categoryId, search, platform);
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, Long userGroupId, boolean admin) {
        return catalogService.listGoods(categoryId, search, platform, userGroupId, admin);
    }

    public Optional<GoodsItem> findGoods(Long id) {
        return catalogService.findGoods(id);
    }

    public Optional<GoodsItem> findGoods(Long id, Long userGroupId, boolean admin) {
        return catalogService.findGoods(id, userGroupId, admin);
    }

    public Optional<GoodsItem> findGoods(Long id, Long userGroupId, boolean admin, String platform) {
        return catalogService.findGoods(id, userGroupId, admin, platform);
    }

    public List<PriceTemplateItem> listPriceTemplates() {
        return catalogService.listPriceTemplates();
    }

    public List<PriceTemplateItem> savePriceTemplates(List<PriceTemplateItem> request) {
        return catalogService.savePriceTemplates(request);
    }

    public List<RechargeFieldItem> listRechargeFields(Boolean enabled) {
        return catalogService.listRechargeFields(enabled);
    }

    public synchronized RechargeFieldItem createRechargeField(RechargeFieldRequest request) {
        return catalogService.createRechargeField(request);
    }

    public synchronized RechargeFieldItem updateRechargeField(Long id, RechargeFieldRequest request) {
        return catalogService.updateRechargeField(id, request);
    }

    public synchronized RechargeFieldItem updateRechargeFieldEnabled(Long id, boolean enabled) {
        return catalogService.updateRechargeFieldEnabled(id, enabled);
    }

    public synchronized void deleteRechargeField(Long id) {
        catalogService.deleteRechargeField(id);
    }

    public List<GoodsChannelItem> listGoodsChannels(Long targetGoodsId) {
        return catalogService.listGoodsChannels(targetGoodsId);
    }

    public GoodsChannelItem createGoodsChannel(Long targetGoodsId, CreateGoodsChannelRequest request) {
        return catalogService.createGoodsChannel(targetGoodsId, request);
    }

    public void deleteGoodsChannel(Long targetGoodsId, Long targetChannelId) {
        catalogService.deleteGoodsChannel(targetGoodsId, targetChannelId);
    }

    public void deleteGoods(Long targetGoodsId) {
        catalogService.deleteGoods(targetGoodsId);
    }

    public GoodsItem createGoods(CreateGoodsRequest request) {
        return catalogService.createGoods(request);
    }

    public GoodsItem updateGoods(Long id, CreateGoodsRequest request) {
        return catalogService.updateGoods(id, request);
    }

    public synchronized int repairBenefitDurationsFromTitles() {
        return catalogService.repairBenefitDurationsFromTitles();
    }

    private void applyMonitoredGoodsUpdate(GoodsItem next) {
        catalogService.applyMonitoredGoodsUpdate(next);
    }

    private Optional<GoodsItem> findGoodsSnapshot(Long id) {
        return catalogService.findGoodsSnapshot(id);
    }

    private List<GoodsItem> allGoodsSnapshots() {
        return catalogService.allGoodsSnapshots();
    }

    private Optional<CategoryItem> findCategorySnapshot(Long id) {
        return catalogService.findCategorySnapshot(id);
    }

    private Set<Long> categoryTreeIds(Long rootId) {
        return catalogService.categoryTreeIds(rootId);
    }

    private List<GoodsChannelItem> allGoodsChannelSnapshots() {
        return catalogService.allGoodsChannelSnapshots();
    }

    private Optional<GoodsChannelItem> findGoodsChannelSnapshot(Long id) {
        return catalogService.findGoodsChannelSnapshot(id);
    }

    private void persistGoodsChannel(GoodsChannelItem item) {
        catalogService.persistGoodsChannel(item);
    }

    private void persistSupplier(SupplierItem item) {
        catalogService.persistSupplier(item);
    }

    private void deletePersistentSupplier(Long id) {
        catalogService.deletePersistentSupplier(id);
    }

    private Optional<List<SupplierItem>> persistentSuppliers() {
        return catalogService.persistentSuppliers();
    }

    private void refreshGoodsStock(Long targetGoodsId) {
        catalogService.refreshGoodsStock(targetGoodsId);
    }

    private void refreshGoodsStockForCardKind(Long targetCardKindId) {
        catalogService.refreshGoodsStockForCardKind(targetCardKindId);
    }

    private GoodsItem refreshStock(GoodsItem item) {
        return catalogService.refreshStock(item);
    }

    private GoodsItem withEffectivePrice(GoodsItem item, Long userGroupId) {
        return catalogService.withEffectivePrice(item, userGroupId);
    }

    private void validateGoodsSalePlatform(GoodsItem item, String platform) {
        catalogService.validateGoodsSalePlatform(item, platform);
    }

    private List<GoodsIntegrationItem> normalizeIntegrations(List<GoodsIntegrationItem> integrations) {
        return catalogService.normalizeIntegrations(integrations);
    }

    private boolean integrationChanged(GoodsIntegrationItem oldItem, GoodsIntegrationItem nextItem) {
        return catalogService.integrationChanged(oldItem, nextItem);
    }

    private List<String> normalizePlatforms(List<String> platforms) {
        return catalogService.normalizePlatforms(platforms);
    }

    private String normalizeSalePlatform(String platform) {
        return catalogService.normalizeSalePlatform(platform);
    }

    private List<String> normalizeTextList(List<String> values) {
        return catalogService.normalizeTextList(values);
    }

    private List<String> normalizedBenefitDurations(List<String> durations, String title) {
        return catalogService.normalizedBenefitDurations(durations, title);
    }

    private String inferredPriceLimitText(String... titles) {
        return catalogService.inferredPriceLimitText(titles);
    }

    private boolean titleContainsPriceLimited(String title) {
        return catalogService.titleContainsPriceLimited(title);
    }

    private String normalizeRechargeFieldCode(String value) {
        return catalogService.normalizeRechargeFieldCode(value);
    }

    private String normalizeRechargeFieldInputType(String value) {
        return catalogService.normalizeRechargeFieldInputType(value);
    }

    private List<String> validateEnabledRechargeFieldCodes(List<String> codes) {
        return catalogService.validateEnabledRechargeFieldCodes(codes);
    }

    private Optional<RechargeFieldItem> rechargeFieldByCode(String code) {
        return catalogService.rechargeFieldByCode(code);
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return catalogService.defaultDecimal(value);
    }

    private int defaultInt(Integer value) {
        return catalogService.defaultInt(value);
    }

    private int normalizedPriority(Integer value) {
        return catalogService.normalizedPriority(value);
    }

    private int normalizedChannelTimeout(Integer value) {
        return catalogService.normalizedChannelTimeout(value);
    }

    private void loadPriceTemplates() {
        catalogService.loadPriceTemplates();
    }

    private void seedCategories() {
        catalogService.seedCategories();
    }

    private void seedRechargeFields() {
        catalogService.seedRechargeFields();
    }

    private void seedGoods() {
        catalogService.seedGoods();
    }

    private void seedGoodsChannels() {
        catalogService.seedGoodsChannels();
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

    public SupplierItem createSupplier(CreateSupplierRequest request) {
        synchronized (supplierLock) {
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
    }

    public SupplierItem updateSupplier(Long id, CreateSupplierRequest request) {
        synchronized (supplierLock) {
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
            // 批次3 分发链①：原为 6 个 isXxxPlatform 或串（isApiSupplierPlatform 含 2 家，共 7 家），
            // 改为查注册表 + 适配器声明的 usesIdentityFallback()。
            String userId = supplierAdapters.find(platformType).map(SupplierAdapter::usesIdentityFallback).orElse(false)
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
    }

    public void deleteSupplier(Long id) {
        synchronized (supplierLock) {
            requiredSupplier(id);
            suppliers.remove(id);
            supplierApiKeys.remove(id);
            remoteGoodsSyncResults.remove(id);
            catalogService.goodsChannelsMap().entrySet().removeIf(entry -> Objects.equals(entry.getValue().supplierId(), id));
            deletePersistentSupplier(id);
        }
    }

    public SupplierItem updateSupplierStatus(Long id, boolean enabled) {
        synchronized (supplierLock) {
            SupplierItem item = requiredSupplier(id);
            SupplierItem next = item.withStatus(enabled ? "ENABLED" : "DISABLED");
            suppliers.put(id, next);
            persistSupplier(next);
            return next;
        }
    }

    public SupplierItem refreshSupplierBalance(Long id) {
        SupplierItem item = requiredSupplier(id);
        // 批次3 分发链②：原 7 段 if/else-if + else 抛不支持。
        SupplierAdapter adapter = supplierAdapters.find(item)
            .filter(SupplierAdapter::supportsBalanceRefresh)
            .orElseThrow(() -> new IllegalArgumentException("当前供应商不支持远程刷新余额，请手动维护余额"));
        SupplierItem next = adapter.refreshBalance(supplierContext(item));
        synchronized (supplierLock) {
            suppliers.put(id, next);
            persistSupplier(next);
        }
        return next;
    }

    public SupplierItem testSupplierConnection(Long id) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        // 批次3 分发链③：原为 7 层嵌套三元，未识别的平台回落 item.withBalance(item.balance())。
        SupplierItem next = supplierAdapters.find(item)
            .map(adapter -> adapter.testConnection(supplierContext(item)))
            .orElseGet(() -> item.withBalance(item.balance()));
        synchronized (supplierLock) {
            suppliers.put(id, next);
            persistSupplier(next);
        }
        return next;
    }

    public RemoteGoodsSyncResult syncRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        // 批次3：原为「福禄 or 蜂助手 → platformLabelForManualSupplier 文案」+ supportsRemoteGoodsSync 硬编码，
        // 改为适配器声明能力位 + 适配器自带文案。
        Optional<SupplierAdapter> knownAdapter = supplierAdapters.find(item);
        if (knownAdapter.isPresent() && !knownAdapter.get().supportsRemoteGoodsSync()) {
            throw new IllegalArgumentException(knownAdapter.get().manualGoodsMappingHint());
        }
        if (knownAdapter.isEmpty()) {
            throw new IllegalArgumentException("supplier platformType must support remote goods sync");
        }

        int page = request == null || request.page() == null ? 1 : Math.max(1, request.page());
        int limit = request == null || request.limit() == null ? 20 : Math.max(1, Math.min(request.limit(), 100));
        Long cateId = request == null ? null : request.cateId();
        String keyword = request == null ? "" : defaultText(request.keyword(), "").trim();

        RemoteGoodsSyncResult result = fetchIntegratedRemoteGoods(item, cateId, keyword, page, limit);
        SupplierItem synced = item.withLastSyncAt(result.syncedAt());
        synchronized (supplierLock) {
            remoteGoodsSyncResults.put(id, result);
            suppliers.put(id, synced);
            persistSupplier(synced);
        }
        return result;
    }

    public Optional<RemoteGoodsSyncResult> latestRemoteGoods(Long id) {
        requiredSupplier(id);
        return Optional.ofNullable(remoteGoodsSyncResults.get(id));
    }

    public GoodsIntegrationItem remoteGoodsSnapshot(Long supplierId, String supplierGoodsId) {
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

    public RemoteGoodsSyncResult sourceConnectRemoteGoods(Long id, SyncGoodsRequest request) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }

        // 批次3：原为「福禄专属文案」+ supportsRemoteGoodsSync 硬编码，
        // 改为 sourceConnectUnsupportedHint()（福禄覆写了专属文案，其余走通用文案）。
        SupplierAdapter adapter = supplierAdapters.find(item)
            .orElseThrow(() -> new IllegalArgumentException("当前供应商不支持远程拉取商品，请手动创建或绑定上游商品编码"));
        if (!adapter.supportsRemoteGoodsSync()) {
            throw new IllegalArgumentException(adapter.sourceConnectUnsupportedHint());
        }
        RemoteGoodsSyncResult result = syncRemoteGoods(id, request);
        return result;
    }

    public SourceCloneResult cloneSourceGoods(Long supplierId, SourceCloneRequest request) {
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
                RemoteGoodsItem remote = sourceCloneRemoteGoods(supplier, cloneItem, normalizedId);
                GoodsIntegrationItem remoteIntegration = remoteGoodsIntegration(supplier, remote);
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
                    defaultText(cloneItem.benefitType(), ""),
                    defaultText(cloneItem.benefitBrand(), ""),
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
                synchronized (goodsLock) {
                    Optional<GoodsChannelItem> existing = allGoodsChannelSnapshots().stream()
                        .filter(channel -> Objects.equals(channel.supplierId(), supplierId))
                        .filter(channel -> Objects.equals(channel.supplierGoodsId(), normalizedId))
                        .filter(channel -> findGoodsSnapshot(channel.goodsId()).isPresent())
                        .findFirst();
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
                        catalogService.goodsChannelsMap().put(updatedChannel.id(), updatedChannel);
                        persistGoodsChannel(updatedChannel);
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
                    createdCount++;
                    results.add(new SourceCloneItem(normalizedId, goodsName, "CREATED", created.id(), channel.id(), "已创建本地商品、绑定货源，后续由商品监控刷新"));
                }
            } catch (RuntimeException ex) {
                failedCount++;
                results.add(new SourceCloneItem(normalizedId, normalizedId, "FAILED", null, null, ex.getMessage()));
            }
        }

        return new SourceCloneResult(createdCount, skippedCount, failedCount, List.copyOf(results));
    }

    private RemoteGoodsItem sourceCloneRemoteGoods(SupplierItem supplier, SourceCloneConfigItem cloneItem, String normalizedId) {
        Optional<RemoteGoodsItem> cached = latestRemoteGoods(supplier.id())
            .flatMap(snapshot -> exactRemoteGoods(snapshot.items(), normalizedId));
        if (cached.isPresent()) {
            return cached.get();
        }
        if (cloneItem != null && StringUtils.hasText(cloneItem.name())) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("platform", defaultText(supplier.platformType(), ""));
            raw.put("supplier_goods_id", normalizedId);
            raw.put("source", "source_connect_draft");
            raw.put("note", "批量对接使用本次提交的待对接配置，避免重复请求上游商品详情");
            BigDecimal price = defaultDecimal(cloneItem.price());
            return new RemoteGoodsItem(
                normalizedId,
                cloneItem.name().trim(),
                "DIRECT",
                "",
                "批量对接",
                price,
                cloneItem.originalPrice() == null ? price : cloneItem.originalPrice(),
                cloneItem.stock() == null ? 0 : Math.max(0, cloneItem.stock()),
                defaultText(cloneItem.status(), "ON_SALE"),
                true,
                false,
                false,
                null,
                "",
                null,
                raw
            );
        }
        return fetchRemoteGoodsSnapshot(supplier, normalizedId, true);
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

    /**
     * 批次8C：分页订单查询，取代原来的「全表读出 + 控制层 subList」。
     *
     * <h2>为什么不再顺手 expire 一遍</h2>
     * 原实现在每个列表读里都调 {@code expireStaleUnpaidOrders()}：
     * 一个<b>读接口里的写副作用</b>，而且它只遍历内存 Map，
     * 数据库里的超时未付款订单它根本看不到，所以在持久化模式下等于空转。
     * 批次8A 的 {@code OrderCompensationWorker.closeTimedOutOrders()} 已经每 60s
     * 对着数据库批量关单，这里再扫一遍既无必要，也让分页无法下推
     * （要先加载全表才能扫）。故删除。
     *
     * @param userId 限定用户，null 表示管理端不限用户
     */
    public PageSlice<OrderItem> pageOrders(String search, String status, String goodsType, Long userId, int limit, long offset) {
        return pageOrders(search, status, goodsType, null, userId, limit, offset);
    }

    public PageSlice<OrderItem> pageOrders(
        String search,
        String status,
        String goodsType,
        OffsetDateTime createdFrom,
        Long userId,
        int limit,
        long offset
    ) {
        if (persistentOrderStore != null) {
            try {
                PageSlice<OrderItem> slice = persistentOrderStore.pageOrders(
                    search, status, goodsType, createdFrom, userId, limit, offset
                );
                return new PageSlice<>(
                    slice.items().stream().map(this::withLatestSupplierNames).toList(),
                    slice.total()
                );
            } catch (RuntimeException ex) {
                recordReadFallback("ORDER", "LIST", ex);
            }
        }
        PageSlice<OrderItem> slice = PageSlice.of(
            filterMemoryOrders(search, status, goodsType, createdFrom, userId), limit, offset
        );
        return new PageSlice<>(withOrderPerformance(slice.items()), slice.total());
    }

    private List<OrderItem> withOrderPerformance(List<OrderItem> items) {
        Map<Long, Long> averages = new LinkedHashMap<>();
        Map<Long, Integer> successRates = new LinkedHashMap<>();
        return items.stream()
            .map(item -> item.withOrderPerformance(
                item.goodsId() == null ? null : averages.computeIfAbsent(
                    item.goodsId(), this::recentRechargeDurationAverage
                ),
                item.goodsId() == null ? null : successRates.computeIfAbsent(
                    item.goodsId(), this::todaySuccessRatePercentage
                )
            ))
            .toList();
    }

    private Long recentRechargeDurationAverage(Long goodsId) {
        var average = orders.values().stream()
            .filter(item -> Objects.equals(item.goodsId(), goodsId))
            .filter(item -> item.status() == OrderStatus.DELIVERED)
            .filter(item -> item.createdAt() != null && item.deliveredAt() != null)
            .filter(item -> !item.deliveredAt().isBefore(item.createdAt()))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .limit(100)
            .mapToLong(item -> Duration.between(item.createdAt(), item.deliveredAt()).toSeconds())
            .average();
        return average.isPresent() ? Math.round(average.getAsDouble()) : null;
    }

    private Integer todaySuccessRatePercentage(Long goodsId) {
        ZonedDateTime chinaNow = ZonedDateTime.now(CHINA_ZONE);
        OffsetDateTime todayStart = chinaNow.toLocalDate().atStartOfDay(CHINA_ZONE).toOffsetDateTime();
        OffsetDateTime tomorrowStart = todayStart.plusDays(1);
        List<OrderItem> terminalOrders = orders.values().stream()
            .filter(item -> Objects.equals(item.goodsId(), goodsId))
            .filter(item -> item.createdAt() != null)
            .filter(item -> !item.createdAt().isBefore(todayStart) && item.createdAt().isBefore(tomorrowStart))
            .filter(item -> List.of(
                OrderStatus.DELIVERED,
                OrderStatus.FAILED,
                OrderStatus.CANCELLED,
                OrderStatus.REFUNDED,
                OrderStatus.CLOSED
            ).contains(item.status()))
            .toList();
        if (terminalOrders.isEmpty()) return null;
        long delivered = terminalOrders.stream()
            .filter(item -> item.status() == OrderStatus.DELIVERED)
            .count();
        return (int) Math.round(delivered * 100.0 / terminalOrders.size());
    }

    public OrderSummaryItem summarizeOrders(
        String search,
        String status,
        String goodsType,
        OffsetDateTime createdFrom,
        Long userId
    ) {
        if (persistentOrderStore != null) {
            try {
                return persistentOrderStore.summarizeOrders(search, status, goodsType, createdFrom, userId);
            } catch (RuntimeException ex) {
                recordReadFallback("ORDER", "SUMMARY", ex);
            }
        }
        List<OrderItem> matched = filterMemoryOrders(search, status, goodsType, createdFrom, userId);
        BigDecimal externalAmount = matched.stream()
            .map(OrderItem::externalMaxAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        long missingExternalAmountCount = matched.stream().filter(item -> item.externalMaxAmount() == null).count();
        long activeCount = matched.stream()
            .filter(item -> List.of(OrderStatus.UNPAID, OrderStatus.PROCURING, OrderStatus.WAITING_MANUAL).contains(item.status()))
            .count();
        long deliveredCount = matched.stream().filter(item -> item.status() == OrderStatus.DELIVERED).count();
        long failedCount = matched.stream()
            .filter(item -> List.of(OrderStatus.FAILED, OrderStatus.REFUNDED, OrderStatus.CANCELLED).contains(item.status()))
            .count();
        return new OrderSummaryItem(
            matched.size(), externalAmount, missingExternalAmountCount, activeCount, deliveredCount, failedCount
        );
    }

    /**
     * 内存兜底筛选。仅在持久层缺失（单元测试）或读失败降级时使用，
     * 匹配语义须与 {@code OrderRecordMapper.selectSnapshotPage} 的 SQL 保持一致。
     */
    private List<OrderItem> filterMemoryOrders(
        String search,
        String status,
        String goodsType,
        OffsetDateTime createdFrom,
        Long userId
    ) {
        String keyword = normalize(search);
        String normalizedStatus = normalize(status);
        String normalizedGoodsType = normalize(goodsType);
        return orders.values().stream()
            .filter(order -> userId == null || Objects.equals(order.userId(), userId))
            .filter(order -> !StringUtils.hasText(keyword) || containsOrderKeyword(order, keyword))
            .filter(order -> !StringUtils.hasText(normalizedStatus) || normalize(String.valueOf(order.status())).equals(normalizedStatus))
            .filter(order -> !StringUtils.hasText(normalizedGoodsType) || normalize(String.valueOf(order.goodsType())).equals(normalizedGoodsType))
            .filter(order -> createdFrom == null || (order.createdAt() != null && !order.createdAt().isBefore(createdFrom)))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .map(this::withLatestSupplierNames)
            .toList();
    }

    /** 批次8C：会员方按 requestId 查单，走 (user_id, request_id) 唯一索引而非全量扫。 */
    public Optional<OrderItem> findOrderByRequestId(Long userId, String requestId) {
        if (persistentOrderStore != null) {
            try {
                Optional<OrderItem> found = persistentOrderStore.findOrderByRequestId(userId, requestId);
                if (found.isPresent()) {
                    return found.map(order -> withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(order, false)));
                }
            } catch (RuntimeException ex) {
                recordReadFallback("ORDER", "BY_REQUEST", ex);
            }
        }
        return orders.values().stream()
            .filter(order -> Objects.equals(order.userId(), userId))
            .filter(order -> requestId != null && requestId.equals(order.requestId()))
            .max(Comparator.comparing(OrderItem::createdAt))
            .map(order -> withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(order, false)));
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

    public OrderItem refreshOrderCallbackInfo(String orderNo) {
        OrderItem active;
        synchronized (orderLock) {
            OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
            if (order == null) {
                throw new IllegalArgumentException("order not found");
            }
            active = expireOrderIfNeeded(order, OffsetDateTime.now());
            if (active == null) {
                throw new IllegalArgumentException("order not found");
            }
        }
        return withLatestSupplierNames(refreshUpstreamOrderStatusIfNeeded(active, true));
    }

    public OrderRefreshResult refreshUnfinishedOrderStatuses() {
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

    /** 批次8B：去掉 synchronized，阻塞 IO 由 {@link #applyUpstreamOrderCallback} 移到锁外。 */
    public String handleFuluOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fulu callback body is empty");
        }
        SupplierItem supplier = resolveFuluCallbackSupplier(supplierId, body);
        verifyFuluCallbackSign(body, supplier);
        String bizContent = callbackText(body.get("biz_content"));
        if (!StringUtils.hasText(bizContent)) {
            throw new IllegalArgumentException("fulu callback biz_content is required");
        }
        // 福禄的我方订单号藏在 biz_content 里，必须先解析快照才能定位订单，故这里提前算好。
        UpstreamOrderSnapshot upstream = FuluSupplierAdapter.callbackSnapshot(bizContent, null);
        String orderNo = upstream.externalOrderNo();
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fulu callback customer_order_no is required");
        }
        applyUpstreamOrderCallback("fulu", supplier, orderNo, () -> upstream);
        return "success";
    }

    /** 批次8B：去掉 synchronized，阻塞 IO 由 {@link #applyUpstreamOrderCallback} 移到锁外。 */
    public Map<String, String> handleFengzhushouOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fengzhushou callback body is empty");
        }
        SupplierItem supplier = resolveFengzhushouCallbackSupplier(supplierId, body);
        verifyFengzhushouCallbackSign(body, supplier);
        String orderNo = callbackText(body.get("channelOrderNo"));
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fengzhushou callback channelOrderNo is required");
        }
        applyUpstreamOrderCallback("fengzhushou", supplier, orderNo, () -> FengzhushouSupplierAdapter.callbackSnapshot(
            callbackText(body.get("orderNo")),
            orderNo,
            intValue(body.get("retcode"), 0),
            callbackText(body.get("msg")),
            abbreviate(callbackText(body), 1200)
        ));
        return Map.of("code", "0");
    }

    /** 批次8B：去掉 synchronized，阻塞 IO 由 {@link #applyUpstreamOrderCallback} 移到锁外。 */
    public String handleChengquanOrderCallback(Long supplierId, Map<String, Object> body) {
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
        applyUpstreamOrderCallback("chengquan", supplier, normalizedOrderNo,
            () -> ChengquanSupplierAdapter.callbackSnapshot(
                firstText(callbackText(body.get("cq_order_no")), callbackText(body.get("platform_order_no")), ""),
                normalizedOrderNo,
                firstText(callbackText(body.get("status")), callbackText(body.get("order_status")), ""),
                firstText(callbackText(body.get("message")), callbackText(body.get("msg")), ""),
                decimalValue(callbackText(body.get("amount"))),
                abbreviate(callbackText(body), 1200)
            ));
        return "OK";
    }

    /** 批次8B：去掉 synchronized，阻塞 IO 由 {@link #applyUpstreamOrderCallback} 移到锁外。 */
    public String handleFanchenOrderCallback(Long supplierId, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("fanchen callback body is empty");
        }
        SupplierItem supplier = resolveFanchenCallbackSupplier(supplierId, body);
        verifyFanchenCallbackSign(body, supplier);
        String orderNo = callbackText(body.get("sporderid"));
        if (!StringUtils.hasText(orderNo)) {
            throw new IllegalArgumentException("fanchen callback sporderid is required");
        }
        applyUpstreamOrderCallback("fanchen", supplier, orderNo, () -> FanchenSupplierAdapter.callbackSnapshot(
            callbackText(body.get("orderid")),
            orderNo,
            callbackText(body.get("resultno")),
            callbackText(body.get("remark1")),
            decimalValue(callbackText(body.get("parvalue"))),
            abbreviate(callbackText(body), 1200)
        ));
        return "OK";
    }

    /** 批次8B：去掉 synchronized，阻塞 IO 由 {@link #applyUpstreamOrderCallback} 移到锁外。 */
    public String handleJingzhaoOrderCallback(Long supplierId, Map<String, Object> body) {
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
        String normalizedOrderNo = orderNo;
        applyUpstreamOrderCallback("jingzhao", supplier, normalizedOrderNo,
            () -> JingzhaoSupplierAdapter.callbackSnapshot(statusNode, normalizedOrderNo));
        return "ok";
    }

    /**
     * 批次8B：7 家上游回调的共同尾段，<b>把阻塞 IO 全部挪到锁外</b>。
     *
     * <h2>原来的问题</h2>
     * 5 个 {@code handleXxxOrderCallback} 都是 {@code public synchronized}（锁 {@code this}），
     * 而方法体里有两处会阻塞在网络上：
     * <ol>
     *   <li>{@link #upstreamGoodsSnapshot} 在本地快照缺失时会真的发 HTTP 去问上游商品；</li>
     *   <li>{@link #publishOrder} → WebSocket {@code session.sendMessage}，
     *       客户端 TCP 缓冲写满时同样会阻塞。</li>
     * </ol>
     * 本类有 ~49 个 {@code public synchronized} 方法共用 {@code this} 这一把锁，
     * 因此任意一家上游变慢（或某个后台页签的 WebSocket 卡住），都会把下单、登录、
     * 支付回调一起堵死 —— 上游抖动直接变成全站不可用。
     *
     * <h2>三段式</h2>
     * <ol>
     *   <li><b>锁外</b>：读订单、定位渠道尝试、做参数校验（读的是 ConcurrentHashMap /
     *       持久化快照，本身线程安全）；</li>
     *   <li><b>锁外</b>：发 HTTP 取上游商品快照；</li>
     *   <li><b>锁内</b>：{@code synchronized (this)} 重读订单、重定位尝试、合并落库。</li>
     *   <li><b>锁外</b>：推送实时事件。</li>
     * </ol>
     *
     * <h2>为什么第 3 段仍锁 this、而不是换成 orderLock</h2>
     * 换锁会新增一条 {@code this → orderLock} 的加锁边，而本类另有多处
     * 「先 orderLock 再调 synchronized 方法」的路径，两者并存就是死锁。
     * 本批次的目标是把 IO 移出锁，不是改锁的拓扑结构，因此临界区保持原样锁 {@code this}，
     * 只是缩短到「纯内存合并 + 落库」。
     *
     * <h2>HTTP 期间订单可能已被改动</h2>
     * 所以第 3 段必须<b>重读</b>订单，而不是复用第 1 段那份快照：
     * 否则回调会用一份过期快照覆盖掉这期间补偿任务/人工处理写入的结果（丢更新）。
     * 重读后订单消失或渠道尝试不再匹配，就按回调失败处理。
     *
     * @param supplierLabel 上游标识，仅用于异常文案，保持与原实现逐字一致
     * @param snapshotSupplier 回调报文解析成 {@link UpstreamOrderSnapshot} 的动作。
     *        传 Supplier 而不是现成对象，是为了让解析发生在「订单校验之后」，
     *        与各家原实现的报错先后顺序完全一致。
     */
    private OrderItem applyUpstreamOrderCallback(
        String supplierLabel,
        SupplierItem supplier,
        String orderNo,
        java.util.function.Supplier<UpstreamOrderSnapshot> snapshotSupplier
    ) {
        // ---- 第 1 段：锁外读取与校验
        OrderItem order = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        ChannelAttemptItem successAttempt = callbackAttemptOf(order, supplier);
        if (successAttempt == null) {
            throw new IllegalArgumentException(supplierLabel + " callback order channel mismatch");
        }
        UpstreamOrderSnapshot upstream = snapshotSupplier.get();

        // ---- 第 2 段：锁外 HTTP（上游商品快照，取不到就是 null，不影响状态推进）
        GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);

        // ---- 第 3 段：锁内合并落库，纯内存 + DB，无网络
        OrderItem next;
        synchronized (this) {
            OrderItem current = persistentOrder(orderNo).orElseGet(() -> orders.get(orderNo));
            if (current == null) {
                throw new IllegalArgumentException("order not found");
            }
            ChannelAttemptItem currentAttempt = callbackAttemptOf(current, supplier);
            if (currentAttempt == null) {
                throw new IllegalArgumentException(supplierLabel + " callback order channel mismatch");
            }
            List<ChannelAttemptItem> nextAttempts = new ArrayList<>(current.channelAttempts());
            int index = nextAttempts.lastIndexOf(currentAttempt);
            if (index >= 0) {
                nextAttempts.set(index, enrichAttempt(currentAttempt, upstream, remote));
            }
            OrderStatus nextStatus = upstream.resolvedLocalStatus(current.status());
            // 重复回调不得刷新发货时间：deliveredAt 必须停在「第一次真的发货」那一刻，
            // 否则上游每重推一次，报表里的发货时长就被抹掉一次。
            OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED
                ? (current.deliveredAt() == null ? OffsetDateTime.now() : current.deliveredAt())
                : current.deliveredAt();
            next = current
                .withUpstreamOrderNo(firstText(upstream.upstreamOrderNo(), current.upstreamOrderNo(), ""))
                .withProcurementResult(
                    nextStatus,
                    upstream.mergedDeliveryItems(current.deliveryItems()),
                    List.copyOf(nextAttempts),
                    upstream.deliveryMessage(),
                    current.paidAt(),
                    deliveredAt
                );
            orders.put(next.orderNo(), next);
            persistOrderSnapshot(next);
        }

        // ---- 第 4 段：锁外推送，WebSocket 写阻塞不再牵连其他请求
        publishOrder(next);
        return next;
    }

    /**
     * 回调所属的渠道尝试：同一供应商最后一次「确实提交过上游」的那条。
     *
     * <h2>为什么不能只认 status ∈ {SUCCESS, PROCURING}</h2>
     * 原实现是这么过滤的，但 {@link #enrichAttempt} 会把该尝试的 {@code status}
     * <b>覆写成订单的本地状态</b>（成功回调后变成 {@code DELIVERED}）。
     * 于是同一笔订单第二次收到回调时就再也匹配不到渠道，抛
     * {@code xxx callback order channel mismatch}。
     *
     * <p>而上游回调普遍是「至少一次」投递：重推、网络重试、我方 5xx 后的补推
     * 都会产生第二次回调。原实现下这些重推<b>必然</b>失败，上游侧会持续重试并
     * 最终把我方标记为回调不可达。
     *
     * <h2>放宽到什么程度</h2>
     * 判定依据从「当前状态」换成「是否曾经提交过上游」这个<b>不会被回调改写</b>的事实：
     * <ul>
     *   <li>{@code SUCCESS} / {@code PROCURING}：首次回调，尚未被 enrich 覆写；</li>
     *   <li>{@code callbackStatus} 非空：只有 {@link #enrichAttempt} 会写这个字段，
     *       非空即证明这条尝试已经收到过上游应答 —— 正是重复回调的情形。</li>
     * </ul>
     * 两者都不满足（例如 {@code FAILED} 且从未收到应答，即压根没提交成功）时仍返回 null，
     * 保持「真正的供应商/渠道不匹配」依旧报错，不把串单当成正常回调吞掉。
     *
     * <p>幂等性不靠这里的过滤保证，而靠调用方重算后写入相同结果：
     * 重复回调最终收敛到同一状态，不会重复退款或重复归还库存（那些动作各有自己的幂等键）。
     */
    private ChannelAttemptItem callbackAttemptOf(OrderItem order, SupplierItem supplier) {
        if (order == null || order.channelAttempts() == null) {
            return null;
        }
        return order.channelAttempts().stream()
            .filter(attempt -> Objects.equals(attempt.supplierId(), supplier.id()))
            .filter(this::submittedToUpstream)
            .reduce((first, second) -> second)
            .orElse(null);
    }

    /** 该渠道尝试是否确实提交到过上游（据此判断回调是否属于本单）。 */
    private boolean submittedToUpstream(ChannelAttemptItem attempt) {
        return "SUCCESS".equals(attempt.status())
            || "PROCURING".equals(attempt.status())
            || StringUtils.hasText(attempt.callbackStatus());
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

    /**
     * 批次8C：支付流水分页。
     *
     * <p>持久层读失败时记一条 {@code PERSISTENCE_READ_FALLBACK} 后降级到内存切页，
     * 与 {@link #pageOrders} 同一套约定：列表页宁可显示内存里的近期数据，也不要整页报错。
     * 内存兜底的排序键与 SQL 的 {@code created_at DESC} 对齐，保证两条路径页序一致。
     */
    public PageSlice<PaymentItem> pagePayments(int limit, long offset) {
        if (persistentOrderStore != null) {
            try {
                return persistentOrderStore.pagePayments(limit, offset);
            } catch (RuntimeException ex) {
                recordReadFallback("PAYMENT", "LIST", ex);
            }
        }
        return PageSlice.of(
            payments.values().stream()
                .sorted(Comparator.comparing(PaymentItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
    }

    /**
     * 批次8C：支付回调日志分页。
     *
     * <p>持久层读失败时按既有约定记一条 {@code PERSISTENCE_READ_FALLBACK} 再降级到内存，
     * 而不是让异常冒到控制层 —— DB 抖动时管理端至少还能看到内存里的近期回调。
     */
    public PageSlice<PaymentCallbackLogItem> pagePaymentCallbackLogs(int limit, long offset) {
        if (persistentOrderStore != null) {
            try {
                return persistentOrderStore.pagePaymentCallbackLogs(limit, offset);
            } catch (RuntimeException ex) {
                recordReadFallback("PAYMENT_CALLBACK", "LIST", ex);
            }
        }
        return PageSlice.of(
            paymentCallbackLogs.values().stream()
                .sorted(Comparator.comparing(PaymentCallbackLogItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
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

    /** 批次8C：退款流水分页，降级语义同 {@link #pagePayments}。 */
    public PageSlice<RefundItem> pageRefunds(int limit, long offset) {
        if (persistentOrderStore != null) {
            try {
                return persistentOrderStore.pageRefunds(limit, offset);
            } catch (RuntimeException ex) {
                recordReadFallback("REFUND", "LIST", ex);
            }
        }
        return PageSlice.of(
            refunds.values().stream()
                .sorted(Comparator.comparing(RefundItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
    }

    /** 批次8C：短信日志分页。降级判断在 {@code AuditService} 内部，这里只做透传。 */
    public PageSlice<SmsLogItem> pageSmsLogs(int limit, long offset) {
        return auditService.pageSmsLogs(limit, offset);
    }

    /** 批次8C：操作日志分页。降级判断在 {@code AuditService} 内部，这里只做透传。 */
    public PageSlice<OperationLogItem> pageOperationLogs(int limit, long offset) {
        return auditService.pageOperationLogs(limit, offset);
    }

    public List<OpenApiLogItem> listOpenApiLogs() {
        return auditService.listOpenApiLogs();
    }

    /** 批次8C：开放接口日志分页。降级判断在 {@code AuditService} 内部，这里只做透传。 */
    public PageSlice<OpenApiLogItem> pageOpenApiLogs(int limit, long offset) {
        return auditService.pageOpenApiLogs(limit, offset);
    }









    public OrderItem createMemberOrder(CreateOrderRequest request, Long userId) {
        return createMemberOrder(request, userId, "");
    }

    public OrderItem createMemberOrder(CreateOrderRequest request, Long userId, String orderIp) {
        return createMemberOrder(request, userId, orderIp, null);
    }

    OrderItem createMemberOrder(
        CreateOrderRequest request,
        Long userId,
        String orderIp,
        BigDecimal externalMaxAmount
    ) {
        OrderItem order = createOrder(request, userId, orderIp, "api", externalMaxAmount);
        if (!OrderStateMachine.canStartMockPayment(order.status())) {
            return order;
        }
        return payOrder(order.orderNo(), userId, new PayOrderRequest("balance", "member-api"));
    }

    public OrderItem handlePaymentCallback(String provider, PaymentCallbackRequest request) {
        OrderItem paidOrder = null;
        OffsetDateTime paidAt = null;
        synchronized (orderLock) {
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
            try {
                ensurePaymentCallbackAmountMatches(payment, order, request);
            } catch (IllegalArgumentException ex) {
                recordPaymentCallback(provider, request, "FAILED", ex.getMessage());
                throw ex;
            }
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
            paidAt = OffsetDateTime.now();
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
            settleCallbackFunds(order, paid);
            recordPaymentCallback(provider, request, "SUCCESS", "callback accepted");
            appendOperation("PAYMENT_CALLBACK_SUCCESS", "PAYMENT", paid.paymentNo(), provider + " callback accepted");
            paidOrder = order.withPayment(paid.paymentNo(), paid.method());
        }
        return dispatchPaidOrder(paidOrder, paidAt);
    }

    /**
     * 外部渠道回调成功后的资金记账。
     *
     * <p>钱是从渠道进来的，又立刻被这笔订单消耗掉，所以记<b>一对</b>流水：
     * CREDIT(PAYMENT_SETTLE, paymentNo) + DEBIT(ORDER_PAY, orderNo)，净额为 0。
     * 用户余额不受影响，但"这笔支付记过账"这件事在库里可证明；
     * 两条流水各带独立幂等键，回调重放不会重复记账。
     */
    private void settleCallbackFunds(OrderItem order, PaymentItem payment) {
        if (!fundsLedgerEnabled()) {
            return;
        }
        fundsLedgerStore.settleExternalPayment(
            order.userId(),
            payment.amount() == null ? order.payAmount() : payment.amount(),
            payment.paymentNo(),
            order.orderNo()
        );
        userService.usersMap().remove(order.userId());
    }

    private void ensurePaymentCallbackMatches(PaymentItem payment, PaymentCallbackRequest request) {
        String callbackOrderNo = request == null ? "" : defaultText(request.orderNo(), "");
        if (StringUtils.hasText(callbackOrderNo) && !Objects.equals(callbackOrderNo, payment.orderNo())) {
            throw new IllegalArgumentException("payment callback order mismatch");
        }
    }

    /**
     * 回调金额必须等于订单应付金额（批次5 / B1 最关键的一条）。
     *
     * <p>光把 amount 塞进签名串只能防"改了金额但没重算签名"，防不住上游/中间人用
     * <b>自己算得出的合法签名</b>声明一个更小的金额。所以必须拿回调金额和本地账目对账：
     * 以 {@code payment.amount()} 为准（它在建单时由 {@code order.payAmount()} 派生），
     * 缺失时回落到 {@code order.payAmount()}，用 {@code compareTo} 比较避免 12 与 12.00 误判。
     *
     * <p>过渡期（strict=false 且上游尚未升级）里 amount 为 null，此处放行；
     * 一旦上游带了 amount，验签器会强制要求 v2 全套字段，金额也就必然走到这里被对账。
     */
    private void ensurePaymentCallbackAmountMatches(PaymentItem payment, OrderItem order, PaymentCallbackRequest request) {
        BigDecimal callbackAmount = request == null ? null : request.amount();
        if (callbackAmount == null) {
            return;
        }
        BigDecimal expected = payment.amount() == null ? order.payAmount() : payment.amount();
        if (expected == null) {
            throw new IllegalArgumentException("payment callback amount unverifiable");
        }
        if (callbackAmount.compareTo(expected) != 0) {
            appendOperation(
                "PAYMENT_CALLBACK_AMOUNT_MISMATCH",
                "PAYMENT",
                defaultText(payment.paymentNo(), ""),
                "callback=" + callbackAmount.toPlainString() + " expected=" + expected.toPlainString()
            );
            throw new IllegalArgumentException("payment callback amount mismatch");
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

    public OrderItem retryProcurement(String orderNo) {
        OrderItem order;
        synchronized (orderLock) {
            order = requiredOrder(orderNo);
            OrderStateMachine.assertCanRetryProcurement(order);
        }
        OrderItem next = procureWithFallback(order, "管理员手动重试");
        saveOrder(next);
        appendOperation("ORDER_RETRY", "ORDER", orderNo, "manual retry");
        publishOrder(next);
        return next;
    }

    public OrderItem retryProcurementWithChannel(String orderNo, Long channelId) {
        OrderItem order;
        synchronized (orderLock) {
            order = requiredOrder(orderNo);
            OrderStateMachine.assertCanRetryProcurement(order);
        }
        GoodsChannelItem channel = catalogService.goodsChannelsMap().get(channelId);
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
        } else {
            ProcurementPrice price;
            try {
                price = procurementPrice(order, channel);
            } catch (RuntimeException ex) {
                attempts.add(attempt(channel, "FAILED", "指定渠道采购价格不可用：" + ex.getMessage()));
                price = null;
            }
            if (price != null && supplier.balance() != null && supplier.balance().compareTo(price.totalCost()) < 0) {
                attempts.add(attempt(channel, price, "FAILED", "指定渠道失败：供应商余额不足"));
                price = null;
            }
            if (price != null) {
                try {
                    ProcurementSubmitResult result = submitProcurementOrder(order, channel, supplier, price);
                    attempts.add(attempt(channel, price, "SUCCESS", result.attemptMessage()));
                    OrderItem procuring = order
                        .withUpstreamOrderNo(firstText(result.upstreamOrderNo(), order.upstreamOrderNo(), ""))
                        .withProcurementResult(
                            OrderStatus.PROCURING,
                            result.deliveryItems(),
                            List.copyOf(attempts),
                            "指定渠道重试成功：已提交到 " + channel.supplierName() + "，等待上游处理",
                            order.paidAt() == null ? OffsetDateTime.now() : order.paidAt(),
                            null
                        );
                    saveOrder(procuring);
                    appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel submitted to upstream");
                    publishOrder(procuring);
                    return procuring;
                } catch (SupplierTransportException ex) {
                    // 缺陷 A4：结果未知不能判失败，转中间态等待上游确认。
                    OrderItem unknown = unknownProcurementResult(order, channel, price, attempts, "指定渠道重试", ex);
                    saveOrder(unknown);
                    appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel result unknown, kept procuring");
                    publishOrder(unknown);
                    return unknown;
                } catch (RuntimeException ex) {
                    attempts.add(attempt(channel, price, "FAILED", "指定渠道提交失败：" + ex.getMessage()));
                }
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
        saveOrder(failed);
        appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel retry failed");
        publishOrder(failed);
        return failed;
    }

    /**
     * 管理员余额退款。
     *
     * <p>批次4 放开了一处会挡住人工补救的判断：原来只要订单状态已是 REFUNDED 就<b>直接 return</b>，
     * 而历史上大量订单被标成 REFUNDED 却<b>从未真正退钱</b>（见 {@link #createRefund} 的注释）。
     * 那个 early return 等于告诉运营"这单已经退过了"，让受害用户永远拿不回钱。
     *
     * <p>现在改成看<b>资金事实</b>而不是订单状态：只有 {@code user_balance_transactions} 里
     * 确实存在 (ORDER_REFUND, orderNo) 这条 CREDIT 流水，才认为退款已完成并短路；
     * 否则即使订单已是 REFUNDED 也继续执行补退。重复调用的安全性由唯一键保证，不靠状态判断。
     */
    public OrderItem refundOrder(String orderNo) {
        synchronized (orderLock) {
            OrderItem order = requiredOrder(orderNo);
            if (order.status() == OrderStatus.REFUNDED && refundAlreadySettled(orderNo)) {
                return order;
            }
            OrderStateMachine.assertCanRefund(order);
            PaymentItem payment = findPaymentSnapshot(order.paymentNo())
                .orElseThrow(() -> new IllegalStateException("订单支付记录不存在，无法退款"));
            if (!"SUCCESS".equals(payment.status())) {
                throw new IllegalStateException("订单支付尚未成功，无法退款");
            }
            if (!"balance".equals(normalizePayMethod(payment.method()))) {
                throw new IllegalStateException("外部支付退款网关尚未接入，不能标记退款成功");
            }

            if (!fundsLedgerEnabled()) {
                // 纯内存路径：保持原行为，已退款则不再重复加钱
                if (order.status() == OrderStatus.REFUNDED) {
                    return order;
                }
                UserItem user = requiredUser(order.userId());
                UserItem credited = withUserBalance(user, defaultDecimal(user.balance()).add(order.payAmount()));
                userService.usersMap().put(user.id(), credited);
                persistUserSnapshot(credited);
            }
            createRefund(order, "管理员手动余额退款");
            restoreStockForRefundedOrder(order);
            OrderItem next = order.withStatus(
                OrderStatus.REFUNDED,
                "余额退款成功",
                order.deliveredAt()
            );
            orders.put(orderNo, next);
            persistOrderSnapshot(next);
            appendOperation("ORDER_REFUND", "ORDER", orderNo, "balance refund succeeded");
            publishOrder(next);
            return next;
        }
    }

    /** 退款是否已经真的落过账（看资金流水，不看订单状态）。 */
    private boolean refundAlreadySettled(String orderNo) {
        return fundsLedgerEnabled() && fundsLedgerStore.refundAlreadySettled(orderNo);
    }

    /**
     * 批次8A：{@link OrderCompensationGateway} 的接缝出口。
     *
     * <p>与 {@link #productMonitorGateway()} 同一手法（内部类而非 {@code implements}）：
     * 补偿任务需要的 8 项能力全是仓储私有方法，让仓储自己实现接口会迫使它们升级为 public。
     */
    public OrderCompensationGateway orderCompensationGateway() {
        return new RepositoryCompensationGateway();
    }

    /**
     * {@link OrderCompensationGateway} 的实现。
     *
     * <p>刻意<b>不</b>暴露「直接改订单状态」：所有流转都得先经 CAS 抢到变更权，
     * {@link #saveAndPublishOrder} 只补 remark / 交付项等描述字段。
     */
    private final class RepositoryCompensationGateway implements OrderCompensationGateway {
        @Override
        public List<OrderItem> orderSnapshots() {
            return InMemoryShopRepository.this.allOrderSnapshots();
        }

        @Override
        public List<OrderItem> unsettledOrderCandidates(
            OffsetDateTime deadline,
            int limit,
            boolean withoutCallback
        ) {
            if (deadline == null || limit <= 0) {
                return List.of();
            }
            if (persistentOrderStore != null) {
                try {
                    return persistentOrderStore.unsettledOrderCandidates(deadline, limit, withoutCallback);
                } catch (RuntimeException ex) {
                    recordReadFallback("ORDER", "UNSETTLED_CANDIDATES", ex);
                    return List.of();
                }
            }
            return orders.values().stream()
                .filter(order -> order != null
                    && order.goodsType() == GoodsType.DIRECT
                    && (order.status() == OrderStatus.PROCURING || order.status() == OrderStatus.DELIVERING))
                .filter(order -> {
                    OffsetDateTime since = order.paidAt() == null ? order.createdAt() : order.paidAt();
                    return since != null && since.isBefore(deadline);
                })
                .filter(order -> !withoutCallback || !callbackSentOnSubmit(order))
                .sorted(Comparator.comparing(
                    order -> order.paidAt() == null ? order.createdAt() : order.paidAt()))
                .limit(limit)
                .toList();
        }

        @Override
        public boolean fundsLedgerEnabled() {
            return InMemoryShopRepository.this.fundsLedgerEnabled();
        }

        @Override
        public String currentOrderStatus(String orderNo) {
            return fundsLedgerEnabled() ? fundsLedgerStore.currentStatus(orderNo) : null;
        }

        @Override
        public boolean compareAndSetOrderStatus(String orderNo, OrderStatus expected, OrderStatus next) {
            return fundsLedgerEnabled()
                && fundsLedgerStore.compareAndSetStatus(orderNo, expected.name(), next.name());
        }

        @Override
        public boolean compareAndSetOrderStatusFromEither(
            String orderNo, OrderStatus expectedA, OrderStatus expectedB, OrderStatus next
        ) {
            return fundsLedgerEnabled() && fundsLedgerStore.compareAndSetStatusFromEither(
                orderNo, expectedA.name(), expectedB.name(), next.name());
        }

        @Override
        public void restoreReservedStock(OrderItem order) {
            InMemoryShopRepository.this.restoreStockForRefundedOrder(order);
        }

        /**
         * 退款：先建退款单，再由 {@link FundsLedgerStore#refundToBalance} 在同一事务里
         * 加回余额 + 记 CREDIT 流水，幂等键 {@code uk_balance_tx_biz (ORDER_REFUND, orderNo)}。
         * 补偿任务重复跑到同一笔单时，第二次是空操作而不是二次退款。
         */
        @Override
        public void refundToBalance(OrderItem order, String reason) {
            if (!fundsLedgerEnabled()) {
                return;
            }
            RefundItem refund = InMemoryShopRepository.this.createRefund(order, reason);
            fundsLedgerStore.refundToBalance(refund, reason);
            userService.usersMap().remove(order.userId());
        }

        @Override
        public void saveAndPublishOrder(OrderItem order) {
            InMemoryShopRepository.this.saveOrder(order);
            InMemoryShopRepository.this.publishOrder(order);
        }

        /**
         * 问上游真实状态。
         *
         * <p>{@code empty()} = 这笔单当前<b>无法查询</b>（无成功渠道 / 供应商已删 /
         * 适配器不支持查单 / 占位地址），调用方跳过。异常按批次3 的分类语义原样上抛：
         * {@link SupplierTransportException} 结果未知（调用方必须什么都不做），
         * {@link SupplierBusinessException} 上游明确拒单（可据此判失败）。
         */
        @Override
        public Optional<UpstreamOrderSnapshot> fetchUpstreamOrderStatus(OrderItem order) {
            ChannelAttemptItem attempt = successfulAttemptOf(order);
            if (attempt == null || attempt.supplierId() == null) {
                return Optional.empty();
            }
            SupplierItem supplier = findSupplierSnapshot(attempt.supplierId()).orElse(null);
            if (supplier == null || isPlaceholderBaseUrl(supplier.baseUrl())) {
                return Optional.empty();
            }
            SupplierAdapter adapter = supplierAdapters.find(supplier).orElse(null);
            if (adapter == null) {
                return Optional.empty();
            }
            return Optional.of(
                adapter.fetchOrderStatus(supplierContext(supplier), order, order.status()));
        }

        @Override
        public boolean callbackSentOnSubmit(OrderItem order) {
            ChannelAttemptItem attempt = successfulAttemptOf(order);
            if (attempt == null || attempt.supplierId() == null || !StringUtils.hasText(attempt.callbackUrl())) {
                return false;
            }
            SupplierItem supplier = findSupplierSnapshot(attempt.supplierId()).orElse(null);
            if (supplier == null) {
                return false;
            }
            return SupplierCallbackUrlResolver.callbackSentOnSubmit(attempt.callbackUrl(), supplier);
        }

        @Override
        public OrderItem applyUpstreamSnapshot(
            OrderItem order, UpstreamOrderSnapshot upstream, OrderStatus nextStatus
        ) {
            List<ChannelAttemptItem> nextAttempts = new ArrayList<>(
                order.channelAttempts() == null ? List.of() : order.channelAttempts());
            ChannelAttemptItem attempt = successfulAttemptOf(order);
            if (attempt != null) {
                SupplierItem supplier = attempt.supplierId() == null
                    ? null
                    : findSupplierSnapshot(attempt.supplierId()).orElse(null);
                GoodsIntegrationItem remote = supplier == null
                    ? null
                    : upstreamGoodsSnapshot(supplier, attempt.supplierGoodsId()).orElse(null);
                int index = nextAttempts.lastIndexOf(attempt);
                if (index >= 0) {
                    nextAttempts.set(index, enrichAttempt(attempt, upstream, remote));
                }
            }
            OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED
                ? OffsetDateTime.now()
                : order.deliveredAt();
            return order
                .withUpstreamOrderNo(firstText(upstream.upstreamOrderNo(), order.upstreamOrderNo(), ""))
                .withProcurementResult(
                    nextStatus,
                    upstream.mergedDeliveryItems(order.deliveryItems()),
                    List.copyOf(nextAttempts),
                    upstream.deliveryMessage(),
                    order.paidAt(),
                    deliveredAt
                );
        }

        @Override
        public void recordAudit(String action, String resourceType, String resourceId, String remark) {
            appendOperation(action, resourceType, resourceId, remark);
        }
    }

    /** 订单上最后一次「已提交上游」的渠道尝试，没有则返回 null。 */
    private ChannelAttemptItem successfulAttemptOf(OrderItem order) {
        List<ChannelAttemptItem> attempts = order == null ? null : order.channelAttempts();
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        return attempts.stream()
            .filter(attempt -> "SUCCESS".equals(attempt.status()) || "PROCURING".equals(attempt.status()))
            .reduce((first, second) -> second)
            .orElse(null);
    }

    /**
     * 退款后归还库存。
     *
     * <p>卡密类商品的库存等于可售卡密数，退款时卡已被 {@code releaseCardsForOrder} 之类
     * 的逻辑或人工处理回收，这里只把 goods.stock_count 重新对齐到真实卡数。
     */
    private void restoreStockForRefundedOrder(OrderItem order) {
        if (!fundsLedgerEnabled()) {
            return;
        }
        if (order.goodsType() == GoodsType.CARD) {
            refreshGoodsStock(order.goodsId());
            return;
        }
        fundsLedgerStore.restoreStock(order.goodsId(), order.quantity());
        catalogService.goodsMap().remove(order.goodsId());
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
        auditService.removeSmsLogsByOrder(orderNo);
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



    public OrderItem createOrder(CreateOrderRequest request) {
        return createOrder(request, 90001L);
    }

    public OrderItem createOrder(CreateOrderRequest request, Long userId) {
        return createOrder(request, userId, "");
    }

    public OrderItem createOrder(CreateOrderRequest request, Long userId, String orderIp) {
        return createOrder(request, userId, orderIp, null);
    }

    public OrderItem createOrder(CreateOrderRequest request, Long userId, String orderIp, String defaultTerminal) {
        return createOrder(request, userId, orderIp, defaultTerminal, null);
    }

    private OrderItem createOrder(
        CreateOrderRequest request,
        Long userId,
        String orderIp,
        String defaultTerminal,
        BigDecimal externalMaxAmount
    ) {
        long startedAt = System.nanoTime();
        try {
            if (persistentOrderCreationEnabled()) {
                return createPersistentOrder(request, userId, orderIp, defaultTerminal, externalMaxAmount);
            }
            synchronized (orderLock) {
                OrderCreationContext context = orderCreationContext(request, userId, defaultTerminal);
                CreateOrderRequest normalizedRequest = normalizeRechargeRequest(context.item(), request);
                OrderItem idempotentOrder = idempotentOrder(context, normalizedRequest);
                if (idempotentOrder != null) {
                    return idempotentOrder;
                }
                GoodsItem stockedItem = validateAndRefreshOrderGoods(context, normalizedRequest);
                // 纯内存测试路径仍靠 JVM 锁保护；生产路径由 OrderCreationStore 的数据库事务保护。
                boolean stockReserved = reserveGoodsStock(stockedItem, context.quantity());
                try {
                    OrderItem order = buildUnpaidOrder(context, stockedItem, normalizedRequest, orderIp);
                    orders.put(order.orderNo(), order);
                    persistOrderSnapshot(order, externalMaxAmount);
                    publishOrder(order);
                    stockReserved = false;
                    return order;
                } finally {
                    if (stockReserved) {
                        releaseGoodsStock(stockedItem, context.quantity());
                    }
                }
            }
        } finally {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (elapsedMillis >= SLOW_ORDER_CREATION_MILLIS) {
                LOG.warn(
                    "slow order creation: requestId={}, goodsId={}, userId={}, elapsedMs={}",
                    request == null ? "" : defaultText(request.requestId(), ""),
                    request == null ? null : request.goodsId(),
                    userId,
                    elapsedMillis
                );
            }
        }
    }

    private OrderItem createPersistentOrder(
        CreateOrderRequest request,
        Long userId,
        String orderIp,
        String defaultTerminal,
        BigDecimal externalMaxAmount
    ) {
        OrderCreationContext context = orderCreationContext(request, userId, defaultTerminal);
        CreateOrderRequest normalizedRequest = normalizeRechargeRequest(context.item(), request);
        OrderItem idempotentOrder = idempotentOrder(context, normalizedRequest);
        if (idempotentOrder != null) {
            orderCreationStore.saveExternalMaxAmount(
                idempotentOrder.orderNo(), userId, externalMaxAmount
            );
            return idempotentOrder;
        }

        GoodsItem stockedItem = validateAndRefreshOrderGoods(context, normalizedRequest);
        if (stockedItem.type() == GoodsType.CARD
            && (stockedItem.stock() == null || stockedItem.stock() < context.quantity())) {
            throw new IllegalStateException("goods stock is insufficient");
        }
        OrderItem candidate = buildUnpaidOrder(context, stockedItem, normalizedRequest, orderIp);
        OrderCreationStore.CreateResult result = orderCreationStore.create(
            candidate,
            stockedItem.type() != GoodsType.CARD,
            externalMaxAmount
        );
        OrderItem order = result.order();
        if (!sameOrderRequest(order, normalizedRequest, context.sourcePlatform(), context.quantity())) {
            throw new IllegalStateException("requestId already used with different order parameters");
        }

        orders.put(order.orderNo(), order);
        if (result.created()) {
            if (stockedItem.type() != GoodsType.CARD) {
                catalogService.goodsMap().remove(stockedItem.id());
            }
            publishCreatedOrder(order);
        }
        return order;
    }

    private void publishCreatedOrder(OrderItem order) {
        if (orderEventExecutor == null) {
            publishOrder(order);
            return;
        }
        try {
            orderEventExecutor.execute(() -> {
                try {
                    publishOrder(order);
                } catch (RuntimeException ex) {
                    LOG.warn("order realtime publish failed after create: orderNo={}", order.orderNo(), ex);
                }
            });
        } catch (RuntimeException ex) {
            LOG.warn("order realtime publish scheduling failed after create: orderNo={}", order.orderNo(), ex);
        }
    }

    private OrderCreationContext orderCreationContext(
        CreateOrderRequest request,
        Long userId,
        String defaultTerminal
    ) {
        if (request == null) {
            throw new IllegalArgumentException("order request is required");
        }
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
        return new OrderCreationContext(user, item, quantity, orderSource(request, defaultTerminal));
    }

    private OrderItem idempotentOrder(OrderCreationContext context, CreateOrderRequest request) {
        Optional<OrderItem> existing = findIdempotentOrder(context.user().id(), request.requestId());
        if (existing.isEmpty()) {
            return null;
        }
        if (!sameOrderRequest(existing.get(), request, context.sourcePlatform(), context.quantity())) {
            throw new IllegalStateException("requestId already used with different order parameters");
        }
        return existing.get();
    }

    private GoodsItem validateAndRefreshOrderGoods(OrderCreationContext context, CreateOrderRequest request) {
        validateGoodsSalePlatform(context.item(), context.sourcePlatform());
        validateGoodsGroupAccess(context.user(), context.item());
        validateOrderPermission(context.user());
        validatePriceLimitPermission(context.user(), context.item());
        validateRechargeFields(context.item(), request);
        if (context.item().maxBuy() != null && context.quantity() > context.item().maxBuy()) {
            throw new IllegalArgumentException("quantity exceeds goods maxBuy");
        }
        return refreshStock(context.item());
    }

    private OrderItem buildUnpaidOrder(
        OrderCreationContext context,
        GoodsItem stockedItem,
        CreateOrderRequest request,
        String orderIp
    ) {
        GoodsItem pricedItem = withEffectivePrice(stockedItem, context.user().groupId());
        return buildOrder(
            nextOrderNo(context.user().id()),
            context.user(),
            pricedItem,
            context.quantity(),
            request,
            context.sourcePlatform(),
            normalizeClientIp(orderIp),
            List.of(),
            OrderStatus.UNPAID,
            "订单已创建，等待支付",
            OffsetDateTime.now(),
            null,
            null
        );
    }

    private record OrderCreationContext(
        UserItem user,
        GoodsItem item,
        int quantity,
        String sourcePlatform
    ) {
    }

    /**
     * 预占库存，返回 true 表示本次真的扣减了 goods.stock_count（需要在失败时归还）。
     *
     * @throws IllegalStateException 库存不足
     */
    private boolean reserveGoodsStock(GoodsItem item, int quantity) {
        if (item.type() == GoodsType.CARD) {
            // 卡密类：真实库存 = 可售卡密行数，发货时用行锁抢占，此处只校验
            if (item.stock() == null || item.stock() < quantity) {
                throw new IllegalStateException("goods stock is insufficient");
            }
            return false;
        }
        if (fundsLedgerEnabled()) {
            if (!fundsLedgerStore.deductStock(item.id(), quantity)) {
                throw new IllegalStateException("goods stock is insufficient");
            }
            catalogService.goodsMap().remove(item.id());
            return true;
        }
        if (item.stock() == null || item.stock() < quantity) {
            throw new IllegalStateException("goods stock is insufficient");
        }
        catalogService.goodsMap().computeIfPresent(item.id(), (id, current) ->
            current.withStock(defaultInt(current.stock()) - quantity));
        return true;
    }

    private void releaseGoodsStock(GoodsItem item, int quantity) {
        if (item.type() == GoodsType.CARD) {
            return;
        }
        if (fundsLedgerEnabled()) {
            fundsLedgerStore.restoreStock(item.id(), quantity);
            catalogService.goodsMap().remove(item.id());
            return;
        }
        catalogService.goodsMap().computeIfPresent(item.id(), (id, current) ->
            current.withStock(defaultInt(current.stock()) + quantity));
    }

    /**
     * 支付订单。
     *
     * <p>批次4 改造要点（缺陷 A4 + 双扣）：
     * <ol>
     *   <li><b>状态先用 DB CAS 抢占</b>：{@code UPDATE orders SET status='PAYING' WHERE status IN
     *       ('CREATED','UNPAID')}。抢不到说明订单已被取消或已被另一个线程支付，直接失败。
     *       这一步把"支付"与"取消"的互斥从 JVM 锁下移到数据库行锁，跨进程也成立。</li>
     *   <li><b>扣款走条件 UPDATE</b>：不再读内存余额算差值。原双扣的根因正是"读到脏内存余额
     *       481.5 → 减 18.5 → 用 ON DUPLICATE KEY UPDATE balance=VALUES(balance) 绝对覆盖 DB 的 500"，
     *       表现为一笔 18.5 的订单让余额掉了 37。改成 {@code balance = balance - ?} 后，
     *       扣多少就是扣多少，内存脏值再也无法参与算术。</li>
     *   <li>扣款失败必须把状态<b>回滚</b>到原状态，否则订单卡在 PAYING 变成资金黑洞。</li>
     * </ol>
     */
    public OrderItem payOrder(String orderNo, Long userId, PayOrderRequest request) {
        OrderItem paidOrder;
        OffsetDateTime paidAt;
        synchronized (orderLock) {
            OrderItem order = requiredOrder(orderNo);
            if (userId != null && !Objects.equals(order.userId(), userId)) {
                throw new IllegalArgumentException("order not found");
            }
            if (!OrderStateMachine.canStartMockPayment(order.status())) {
                throw new IllegalStateException("订单当前状态不可重复支付");
            }

            paidAt = OffsetDateTime.now();
            String method = normalizePayMethod(request == null ? "" : request.payMethod());
            String terminal = normalizeTerminal(request == null ? "" : request.terminal());
            PaymentChannelItem channel = requireUsablePaymentChannel(method, terminal);
            method = channel.code();

            OrderStatus claimedFrom = claimOrderForPayment(order);
            boolean paymentSettled = false;
            try {
                if ("balance".equals(method)) {
                    debitBalanceForPayment(order);
                    paymentSettled = true;
                } else if (prodProfile) {
                    throw new IllegalStateException("生产环境不允许模拟支付成功，请接入真实支付网关或使用余额支付");
                } else if (!"MOCK".equalsIgnoreCase(defaultText(configService.systemSetting().paymentMode(), "MOCK"))) {
                    throw new IllegalStateException("真实微信/支付宝支付尚未完成网关下单接入，请先使用余额支付或切回模拟支付模式");
                } else {
                    paymentSettled = true;
                }
            } finally {
                if (!paymentSettled && claimedFrom != null) {
                    releaseOrderPaymentClaim(orderNo, claimedFrom);
                }
            }
            PaymentItem payment = createSuccessfulPayment(order, method, paidAt);
            appendOperation("PAYMENT_CREATE", "PAYMENT", payment.paymentNo(), method + " payment succeeded");
            paidOrder = order.withPayment(payment.paymentNo(), payment.method());
        }
        return dispatchPaidOrder(paidOrder, paidAt);
    }

    /**
     * 按支付方式 + 终端解析可用支付通道（供网关支付编排使用）。
     *
     * <p>{@link #requireUsablePaymentChannel} 是私有的，这里开一个窄口给
     * {@link AlipayPaymentFacade}，避免把整个仓储的私有细节暴露出去。
     */
    public PaymentChannelItem resolvePaymentChannel(String method, String terminal) {
        return requireUsablePaymentChannel(method, terminal);
    }

    /**
     * 建一条 <b>PENDING</b> 支付单，用于外部网关支付的"待付款"阶段。
     *
     * <p>与 {@link #createSuccessfulPayment} 的关键区别：<b>不改订单状态、不记资金流水</b>。
     * 此刻用户还没付钱，我方只是生成了一个 {@code out_trade_no} 交给支付宝。
     * 真正的入账发生在异步通知到达后的 {@link #handlePaymentCallback} 里。
     *
     * <p>订单状态刻意<b>保持 UNPAID</b> 而不推进到 PAYING：用户跳到支付宝后放弃付款是常态，
     * 留在 UNPAID 才能让他回来重新发起支付；
     * {@code assertCanAcceptPaymentCallback} 同时接受 UNPAID 与 PAYING，通知照样能落地。
     */
    public PaymentItem createPendingPayment(String orderNo, Long userId, String method, String terminal) {
        synchronized (orderLock) {
            OrderItem order = requiredOrder(orderNo);
            if (userId != null && !Objects.equals(order.userId(), userId)) {
                throw new IllegalArgumentException("order not found");
            }
            if (!OrderStateMachine.canStartMockPayment(order.status())) {
                throw new IllegalStateException("订单当前状态不可重复支付");
            }
            PaymentChannelItem channel = requireUsablePaymentChannel(method, terminal);
            String paymentNo = nextPaymentNo();
            PaymentItem payment = new PaymentItem(
                paymentNo,
                order.orderNo(),
                order.userId(),
                channel.code(),
                order.payAmount(),
                "PENDING",
                // 初始就填 paymentNo：它正是传给支付宝的 out_trade_no，
                // 且 out_trade_no 带唯一键，留空串会让并发待支付流水互相覆盖。
                // 通知到达后这里会被支付宝真实 trade_no 覆盖，同样唯一。
                paymentNo,
                OffsetDateTime.now(),
                null
            );
            payments.put(paymentNo, payment);
            persistPaymentSnapshot(payment);
            appendOperation("PAYMENT_PREPARE", "PAYMENT", paymentNo, channel.code() + " gateway payment prepared");
            return payment;
        }
    }

    /**
     * 按 {@code app_id} 反查支付宝通道，供异步通知在<b>验签之前</b>定位公钥。
     *
     * <p>通知报文里没有我方的通道编码，只有 {@code app_id}；而验签需要该通道配置的支付宝公钥。
     * 因此先用 app_id 找通道、再用它的公钥验签。这一步只是"找钥匙"，
     * 报文可信性完全由随后的 RSA2 验签决定，不因这里的匹配而获得任何信任。
     */
    public Optional<PaymentChannelItem> findAlipayChannelByAppId(String appId) {
        ensurePaymentChannelsReady();
        String normalized = defaultText(appId, "").trim();
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        return paymentChannels.values().stream()
            .filter(item -> "ALIPAY".equalsIgnoreCase(defaultText(item.type(), "")))
            .filter(item -> normalized.equals(defaultText(item.config() == null ? "" : item.config().get("app_id"), "").trim()))
            .findFirst();
    }

    /**
     * 用 DB CAS 抢占支付权，返回被抢占前的状态（用于失败回滚）；纯内存模式返回 null。
     */
    private OrderStatus claimOrderForPayment(OrderItem order) {
        if (!fundsLedgerEnabled()) {
            return null;
        }
        String current = fundsLedgerStore.currentStatus(order.orderNo());
        if (current == null) {
            // 订单快照还没落库（例如刚在内存里创建），退回内存状态机判断
            return null;
        }
        boolean claimed = fundsLedgerStore.compareAndSetStatusFromEither(
            order.orderNo(),
            OrderStatus.CREATED.name(),
            OrderStatus.UNPAID.name(),
            OrderStatus.PAYING.name()
        );
        if (!claimed) {
            String latest = fundsLedgerStore.currentStatus(order.orderNo());
            syncOrderStatusFromDb(order, latest);
            throw new IllegalStateException("订单当前状态不可重复支付");
        }
        return OrderStatus.valueOf(current);
    }

    private void releaseOrderPaymentClaim(String orderNo, OrderStatus original) {
        if (fundsLedgerEnabled()) {
            fundsLedgerStore.compareAndSetStatus(orderNo, OrderStatus.PAYING.name(), original.name());
        }
    }

    /** 把 DB 里的最新状态回灌内存，避免内存继续拿旧状态骗人。 */
    private void syncOrderStatusFromDb(OrderItem order, String dbStatus) {
        if (dbStatus == null) {
            return;
        }
        try {
            OrderStatus parsed = OrderStatus.valueOf(dbStatus);
            if (parsed != order.status()) {
                orders.put(order.orderNo(), order.withStatus(parsed, order.deliveryMessage(), order.deliveredAt()));
            }
        } catch (IllegalArgumentException ignored) {
            // DB 里出现未知状态值时不做猜测，保持内存原样
        }
    }

    /**
     * 余额支付扣款。
     *
     * <p>持久化模式下完全交给 {@link FundsLedgerStore}：条件 UPDATE + DEBIT 流水同事务。
     * 扣完把内存里的用户快照<b>逐出</b>而不是写回——写回就等于重新制造脏缓存。
     */
    private void debitBalanceForPayment(OrderItem order) {
        if (fundsLedgerEnabled()) {
            fundsLedgerStore.debitForOrderPay(
                order.userId(),
                order.payAmount(),
                order.orderNo(),
                "订单支付：" + order.orderNo()
            );
            userService.usersMap().remove(order.userId());
            return;
        }
        debitBalanceInMemory(order);
    }

    /** 纯内存回退路径（无持久化的单元测试用）。 */
    private void debitBalanceInMemory(OrderItem order) {
        UserItem user = requiredUser(order.userId());
        BigDecimal currentBalance = user.balance() == null ? BigDecimal.ZERO : user.balance();
        if (currentBalance.compareTo(order.payAmount()) < 0) {
            throw new IllegalStateException("余额不足，请先充值");
        }
        UserItem debited = withUserBalance(user, currentBalance.subtract(order.payAmount()));
        userService.usersMap().put(user.id(), debited);
        persistUserSnapshot(debited);
    }

    private OrderItem dispatchPaidOrder(OrderItem order, OffsetDateTime paidAt) {
        if (order.goodsType() == GoodsType.CARD) {
            synchronized (orderLock) {
                return deliverCardsAfterPayment(order, paidAt);
            }
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
            saveAndPublishOrder(paidOrder);
            OrderItem next = procureWithFallback(paidOrder, "系统自动采购");
            saveOrder(next);
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
        saveOrder(next);
        publishOrder(next);
        return next;
    }

    public OrderItem cancelOrder(String orderNo) {
        return cancelOrder(orderNo, null);
    }

    /**
     * 取消订单。
     *
     * <p>批次4：去掉方法级 {@code synchronized}（原来锁 {@code this}），改成 DB 状态 CAS。
     * 原实现的致命问题是它与 {@link #payOrder} 锁在<b>两个互不排斥的 monitor</b> 上
     * （cancel 锁 this、pay 锁 orderLock），两者可以同时进入临界区，
     * 于是出现"同一笔订单既支付成功又取消成功"。
     * 现在 {@code UPDATE orders SET status='CANCELLED' WHERE status IN ('CREATED','UNPAID')}
     * 与支付侧的 {@code ... SET status='PAYING' WHERE status IN ('CREATED','UNPAID')}
     * 争抢同一行的行锁，数据库保证只有一个能赢。
     */
    public OrderItem cancelOrder(String orderNo, Long userId) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        if (fundsLedgerEnabled() && fundsLedgerStore.currentStatus(orderNo) != null) {
            boolean cancelled = fundsLedgerStore.compareAndSetStatusFromEither(
                orderNo,
                OrderStatus.CREATED.name(),
                OrderStatus.UNPAID.name(),
                OrderStatus.CANCELLED.name()
            );
            if (!cancelled) {
                syncOrderStatusFromDb(order, fundsLedgerStore.currentStatus(orderNo));
                throw new IllegalStateException("only unpaid orders can be cancelled");
            }
            OrderItem next = order.withStatus(OrderStatus.CANCELLED, "订单已取消", order.deliveredAt());
            orders.put(orderNo, next);
            // CAS 已经把 status 落库并赢下了竞争，这里补齐 remark 等描述字段
            persistOrderSnapshot(next);
            restoreStockForCancelledOrder(next);
            publishOrder(next);
            return next;
        }
        synchronized (orderLock) {
            OrderItem latest = requiredOrder(orderNo);
            OrderStateMachine.assertCanCancel(latest);
            OrderItem next = latest.withStatus(
                OrderStatus.CANCELLED,
                "订单已取消",
                latest.deliveredAt()
            );
            orders.put(orderNo, next);
            persistOrderSnapshot(next);
            publishOrder(next);
            return next;
        }
    }

    /** 取消未支付订单时归还下单阶段预扣的库存（卡密类不占 stock_count，跳过）。 */
    private void restoreStockForCancelledOrder(OrderItem order) {
        if (order.goodsType() == GoodsType.CARD || !fundsLedgerEnabled()) {
            return;
        }
        fundsLedgerStore.restoreStock(order.goodsId(), order.quantity());
        catalogService.goodsMap().remove(order.goodsId());
    }

    private OrderItem deliverCardsAfterPayment(OrderItem order, OffsetDateTime paidAt) {
        GoodsItem item = findGoodsSnapshot(order.goodsId()).orElse(null);
        Long boundCardKindId = item == null ? null : item.cardKindId();
        Optional<List<String>> persistentDelivery = tryDeliverPersistentCards(order, boundCardKindId);
        if (persistentDelivery.isPresent()) {
            // 发完货把标称库存对齐到真实剩余卡密数，否则 goods.stock_count 永远停在发货前的值
            if (boundCardKindId == null) {
                refreshGoodsStock(order.goodsId());
            } else {
                refreshGoodsStockForCardKind(boundCardKindId);
            }
            return completeCardDelivery(order, paidAt, persistentDelivery.get());
        }
        // 缺陷 A2/A3：持久化模式下不再有"内存卡兜底"这条路。
        // 唯一并发安全的发货实现是 PersistentOrderStore.deliverCardsForOrder
        // （FOR UPDATE SKIP LOCKED + 条件 UPDATE ... WHERE status='UNSOLD'）。
        // 它失败就是真的没卡，走缺货/退款流程；绝不能拿空的内存 Map 再"兜"一次，
        // 那只会把同一张卡发两遍或凭空发不存在的卡。
        List<CardSecret> available = fundsLedgerEnabled()
            ? List.of()
            : cards.values().stream()
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
            if (configService.systemSetting().autoRefundEnabled() && order.paymentNo() != null) {
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
            ProcurementPrice price;
            try {
                price = procurementPrice(order, channel);
            } catch (RuntimeException ex) {
                attempts.add(attempt(channel, "FAILED", "采购价格不可用：" + ex.getMessage()));
                continue;
            }
            if (supplier.balance() != null && supplier.balance().compareTo(price.totalCost()) < 0) {
                attempts.add(attempt(channel, price, "FAILED", "供应商余额不足"));
                continue;
            }

            try {
                ProcurementSubmitResult result = submitProcurementOrder(order, channel, supplier, price);
                attempts.add(attempt(channel, price, "SUCCESS", result.attemptMessage()));
                return order
                    .withUpstreamOrderNo(firstText(result.upstreamOrderNo(), order.upstreamOrderNo(), ""))
                    .withProcurementResult(
                        OrderStatus.PROCURING,
                        result.deliveryItems(),
                        List.copyOf(attempts),
                        trigger + "成功：已提交到 " + channel.supplierName() + "，等待上游处理",
                        order.paidAt(),
                        null
                    );
            } catch (SupplierTransportException ex) {
                // 缺陷 A4：超时 / 连接异常 / 5xx / 响应无法解析 —— 上游是否已受理未知。
                // 绝不能置 FAILED（上游可能已扣我方预付款），必须转中间态等待回调或对账，
                // 也不能继续尝试下一个渠道（否则同一笔订单可能在两家上游各下一单）。
                return unknownProcurementResult(order, channel, price, attempts, trigger, ex);
            } catch (RuntimeException ex) {
                // 上游明确拒单（SupplierBusinessException）或本地前置校验失败 —— 可安全判失败并降级下一渠道。
                attempts.add(attempt(channel, price, "FAILED", "提交失败：" + ex.getMessage()));
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

    /** 仅供测试注入 HTTP 桩，验证上游超时/拒单两类语义（缺陷 A4）。生产代码不调用。 */
    void replaceSupplierHttpClientForTest(SupplierHttpClient stub) {
        this.supplierHttp = stub;
    }

    /**
     * 缺陷 A4：上游结果未知时的统一落点。
     *
     * <p>渠道尝试记录状态写 {@code PROCURING}（而非 FAILED），订单转中间态 {@code PROCURING}，
     * 并尽力记录上游订单号（超时场景通常拿不到，此时保留原值）。
     * 后续由 {@code refreshUpstreamOrderStatusIfNeeded} 或上游异步回调收敛为终态。</p>
     */
    private OrderItem unknownProcurementResult(
        OrderItem order,
        GoodsChannelItem channel,
        ProcurementPrice price,
        List<ChannelAttemptItem> attempts,
        String trigger,
        SupplierTransportException ex
    ) {
        List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
        nextAttempts.add(attempt(channel, price, "PROCURING", "上游结果未知，待对账：" + ex.getMessage()));
        return order.withProcurementResult(
            OrderStatus.PROCURING,
            order.deliveryItems() == null ? List.of() : order.deliveryItems(),
            List.copyOf(nextAttempts),
            trigger + "结果未知：" + channel.supplierName() + " 未在超时时间内明确应答，已转采购中等待上游确认",
            order.paidAt(),
            null
        );
    }

    private ProcurementSubmitResult submitProcurementOrder(
        OrderItem order,
        GoodsChannelItem channel,
        SupplierItem supplier,
        ProcurementPrice price
    ) {
        // 批次3 分发链④：原为「6 个 isXxx 或串守卫 + 6 段 if 分发 + 尾部卡速售兜底」。
        SupplierAdapter adapter = supplierAdapters.find(supplier)
            .filter(SupplierAdapter::supportsOrderSubmit)
            .orElseThrow(() -> new IllegalStateException("供应商暂不支持真实下单"));
        if (isPlaceholderBaseUrl(supplier.baseUrl())) {
            throw new IllegalStateException("供应商地址是占位地址，不能真实下单");
        }
        UpstreamSubmitResult result = adapter.submitOrder(supplierContext(supplier), order, channel, price);
        return new ProcurementSubmitResult(
            result.deliveryItems(),
            result.attemptMessage(),
            result.upstreamOrderNo()
        );
    }

    private ProcurementPrice procurementPrice(OrderItem order, GoodsChannelItem channel) {
        GoodsItem goods = findGoodsSnapshot(order.goodsId()).orElse(null);
        ProcurementPrice price = ProcurementPrice.resolve(goods, channel, order.quantity());
        persistProcurementCost(order.orderNo(), price.totalCost());
        return price;
    }

    /** 批次3：新增 upstreamOrderNo，供缺陷 A4 把上游订单号落到 orders.upstream_order_no。 */
    private record ProcurementSubmitResult(
        List<String> deliveryItems,
        String attemptMessage,
        String upstreamOrderNo
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
        // 批次3 分发链⑤：原为「6 个 isXxx 或串守卫 + 6 段 if + 卡速售兜底」，每段都是同一套
        // 「查上游 → enrichAttempt → 映射本地状态 → 合并交付项 → 保存 → 变更时推送」流水，共约 200 行。
        // 现由适配器返回归一化的 UpstreamOrderSnapshot，流水只写一遍。
        SupplierAdapter adapter = supplierAdapters.find(supplier).orElse(null);
        if (adapter == null) {
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
            UpstreamOrderSnapshot upstream = adapter.fetchOrderStatus(supplierContext(supplier), order, order.status());
            GoodsIntegrationItem remote = upstreamGoodsSnapshot(supplier, successAttempt.supplierGoodsId()).orElse(null);
            ChannelAttemptItem enrichedAttempt = enrichAttempt(successAttempt, upstream, remote);
            List<ChannelAttemptItem> nextAttempts = new ArrayList<>(attempts);
            int index = nextAttempts.lastIndexOf(successAttempt);
            if (index >= 0) {
                nextAttempts.set(index, enrichedAttempt);
            }
            OrderStatus nextStatus = upstream.localStatus();
            OffsetDateTime deliveredAt = nextStatus == OrderStatus.DELIVERED ? OffsetDateTime.now() : order.deliveredAt();
            List<String> deliveryItems = upstream.mergedDeliveryItems(order.deliveryItems());
            String message = upstream.deliveryMessage();
            OrderItem next = order
                .withUpstreamOrderNo(firstText(upstream.upstreamOrderNo(), order.upstreamOrderNo(), ""))
                .withProcurementResult(
                    nextStatus,
                    deliveryItems,
                    List.copyOf(nextAttempts),
                    message,
                    order.paidAt(),
                    deliveredAt
                );
            saveOrder(next);
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

    /**
     * 批次3：原 7 个 enrichAttempt 重载（每家一个上游状态 record）合并为一个。
     * 字段取值口径与原实现逐一对齐：上游状态标签、本地状态名、回调文案、价格与商品名回落顺序均不变。
     */
    private ChannelAttemptItem enrichAttempt(
        ChannelAttemptItem attempt,
        UpstreamOrderSnapshot upstream,
        GoodsIntegrationItem remote
    ) {
        String upstreamStatus = upstream.upstreamStatusLabel();
        String callbackMessage = firstText(upstream.hints(), attempt.callbackMessage(), attempt.message());
        BigDecimal price = upstream.totalPrice() == null
            ? (remote == null ? attempt.supplierPrice() : remote.supplierPrice())
            : upstream.totalPrice();
        // 原实现里只有福禄会带上游商品名（productName），其余 6 家直接用远端快照名/尝试记录名。
        // 这里保持完全一致的回落顺序：上游有名字才参与 firstText，否则沿用原来的二选一。
        String goodsName = StringUtils.hasText(upstream.goodsName())
            ? firstText(upstream.goodsName(), remote == null ? "" : remote.supplierGoodsName(), attempt.supplierGoodsName())
            : (remote == null ? attempt.supplierGoodsName() : remote.supplierGoodsName());
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
            attempt.callbackUrl(),
            attempt.priority(),
            upstream.localStatus().name(),
            callbackMessage,
            OffsetDateTime.now()
        );
    }

    private ChannelAttemptItem attempt(GoodsChannelItem channel, String status, String message) {
        return attempt(channel, null, status, message);
    }

    private ChannelAttemptItem attempt(
        GoodsChannelItem channel,
        ProcurementPrice price,
        String status,
        String message
    ) {
        SupplierItem supplier = findSupplierSnapshot(channel.supplierId()).orElse(null);
        String effectiveCallbackUrl = "SUCCESS".equals(status) || "PROCURING".equals(status)
            ? SupplierCallbackUrlResolver.effectiveUrl(configService.outboundPublicBaseUrl(), supplier)
            : "";
        String callbackUrl = SupplierCallbackUrlResolver.callbackSentOnSubmit(effectiveCallbackUrl, supplier)
            ? effectiveCallbackUrl
            : "";
        return new ChannelAttemptItem(
            channel.id(),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            null,
            price == null ? null : price.unitCost(),
            null,
            null,
            null,
            null,
            callbackUrl,
            channel.priority(),
            status,
            message,
            OffsetDateTime.now()
        );
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
        if (deliveredCards.isEmpty() && order.goodsType() == GoodsType.CARD) {
            deliveredCards = order.deliveryItems().stream()
                .filter(StringUtils::hasText)
                .map(this::persistentDeliveryCard)
                .toList();
        }
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

    private DeliveryCardItem persistentDeliveryCard(String content) {
        String[] parts = content.trim().split("\\|", 2);
        return new DeliveryCardItem(
            parts[0],
            parts.length > 1 ? parts[1] : "",
            "卡密已显示，请尽快使用。系统不会在日志中记录明文。"
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

    private void saveOrder(OrderItem order) {
        if (order == null) {
            return;
        }
        synchronized (orderLock) {
            orders.put(order.orderNo(), order);
            persistOrderSnapshot(order);
        }
    }

    private void saveAndPublishOrder(OrderItem order) {
        saveOrder(order);
        publishOrder(order);
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
        return order.withChannelAttempts(nextAttempts);
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
            attempt.callbackUrl(),
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
        if (auditService.hasSmsLog(order.orderNo(), String.valueOf(order.status()))) {
            return;
        }
        String mobile = order.rechargeAccount();
        if (!StringUtils.hasText(mobile) || !mobile.matches("1\\d{10}")) {
            mobile = order.buyerAccount();
        }
        boolean validMobile = StringUtils.hasText(mobile) && mobile.matches("1\\d{10}");
        String content = "订单" + order.orderNo() + "状态：" + order.status() + "，商品：" + order.goodsName();
        SystemSettingItem setting = configService.systemSetting();
        String status = setting.smsEnabled() && validMobile ? "SENT" : "SKIPPED";
        String error = setting.smsEnabled() ? (validMobile ? "" : "手机号不可用") : "短信未启用";
        auditService.appendSmsLog(
            order.orderNo(),
            validMobile ? mobile : "",
            String.valueOf(order.status()),
            content,
            status,
            error,
            OffsetDateTime.now()
        );
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

    /**
     * 批次3 分发链⑥：原为 5 段 if + 逐家写死的 {@code Math.min(limit, 100)}（卡速售不夹取），
     * 现由 {@link SupplierAdapter#maxRemoteGoodsPageSize()} 声明，夹取语义逐家保持不变。
     */
    private RemoteGoodsSyncResult fetchIntegratedRemoteGoods(SupplierItem item, Long cateId, String keyword, int page, int limit) {
        if (isPlaceholderBaseUrl(item.baseUrl())) {
            throw new IllegalArgumentException("供应商 API 地址仍是占位地址，不能拉取上游商品");
        }
        SupplierAdapter adapter = supplierAdapters.require(item);
        if (!adapter.supportsRemoteGoodsSync()) {
            throw new IllegalArgumentException(adapter.manualGoodsMappingHint());
        }
        int effectiveLimit = Math.min(limit, adapter.maxRemoteGoodsPageSize());
        return adapter.fetchRemoteGoods(supplierContext(item), cateId, keyword, page, effectiveLimit);
    }

    /**
     * 商品条目映射端口实现。全部委托回原有私有方法，商品字段映射逻辑零改动。
     */
    private final SupplierGoodsMappingPort goodsMappingPort = new SupplierGoodsMappingPort() {
        @Override
        public RemoteGoodsItem kasushouItem(Long supplierId, JsonNode node, Map<String, String> categoryNames,
                                            String selectedCategoryId, String selectedCategoryName) {
            return remoteGoodsItem(supplierId, node, categoryNames, selectedCategoryId, selectedCategoryName);
        }

        @Override
        public RemoteGoodsItem kakayunItem(Long supplierId, JsonNode node, Map<String, String> categoryNames,
                                           String selectedCategoryId, String selectedCategoryName) {
            return kakayunRemoteGoodsItem(supplierId, node, categoryNames, selectedCategoryId, selectedCategoryName);
        }

        @Override
        public RemoteGoodsItem chengquanItem(Long supplierId, JsonNode node, Map<String, String> categoryNames,
                                             String selectedCategoryId, String selectedCategoryName) {
            return chengquanRemoteGoodsItem(supplierId, node, categoryNames, selectedCategoryId, selectedCategoryName);
        }

        @Override
        public RemoteGoodsItem fanchenItem(Long supplierId, JsonNode node) {
            return fanchenRemoteGoodsItem(supplierId, node);
        }

        @Override
        public RemoteGoodsItem jingzhaoItem(Long supplierId, JsonNode node) {
            return jingzhaoRemoteGoodsItem(supplierId, node);
        }

        @Override
        public List<Map<String, Object>> kasushouCategories(JsonNode data) {
            return InMemoryShopRepository.this.kasushouCategories(data);
        }

        @Override
        public List<Map<String, Object>> kakayunCategories(JsonNode data) {
            return InMemoryShopRepository.this.kakayunCategories(data);
        }

        @Override
        public Map<String, String> categoryNames(List<Map<String, Object>> categories) {
            return remoteCategoryNames(categories);
        }

        @Override
        public String jingzhaoGoodsTypeLabel(String type) {
            return InMemoryShopRepository.this.jingzhaoGoodsTypeLabel(type);
        }

        @Override
        public boolean matchesKeyword(RemoteGoodsItem item, String keyword) {
            return !StringUtils.hasText(keyword)
                || normalize(item.goodsName()).contains(normalize(keyword))
                || normalize(item.supplierGoodsId()).contains(normalize(keyword));
        }

        @Override
        public List<Map<String, Object>> toMapList(JsonNode node) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            return OBJECT_MAPPER.convertValue(node, LIST_MAP_TYPE);
        }
    };

    /** 组装一次适配器调用的上下文。密钥仍由仓储持有，只把本次要用的明文传入。 */
    private SupplierCallContext supplierContext(SupplierItem item) {
        return new SupplierCallContext(
            item,
            resolvedSupplierApiKey(item),
            supplierHttp,
            goodsMappingPort,
            SupplierCallbackUrlResolver.effectiveUrl(configService.outboundPublicBaseUrl(), item)
        );
    }

    /** 原 7 个 xxxApiKey()/xxxSecret()/xxxKey() 逻辑完全一致：先取内存明文，回落 item.apiKey()。 */
    private String resolvedSupplierApiKey(SupplierItem item) {
        String apiKey = supplierApiKeys.get(item.id());
        if (!StringUtils.hasText(apiKey)) {
            apiKey = item.apiKey();
        }
        return defaultText(apiKey, "").trim();
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
        String expected = JingzhaoSignatureUtil.callbackSign(body, jingzhaoKey(supplier));
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

    /**
     * 批次3：原为 7 个 {@code isXxxSupplier} + 7 个 {@code isXxxPlatform}（各自一份别名表），
     * 别名表已原样搬到 {@link SupplierPlatform} 的枚举常量上（含 {@code -} 转 {@code _} 归一化）。
     * 这里只剩「回调报文按 app_key/账号找供应商」这一类身份判定还需要按家过滤。
     */
    private boolean isPlatform(SupplierItem item, SupplierPlatform platform) {
        return item != null && platform.matches(item.platformType());
    }

    private boolean isKasushouSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.KASUSHOU);
    }

    private boolean isKakayunSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.KAKAYUN);
    }

    private boolean isFuluSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.FULU);
    }

    private boolean isFengzhushouSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.FENGZHUSHOU);
    }

    private boolean isChengquanSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.CHENGQUAN);
    }

    private boolean isFanchenSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.FANCHEN);
    }

    private boolean isJingzhaoSupplier(SupplierItem item) {
        return isPlatform(item, SupplierPlatform.JINGZHAO);
    }

    /**
     * 批次3：原为「isApiSupplier(卡速售 or 咔咔云) or 橙券 or 梵尘 or 京兆」的 4 段或串，
     * 现改为查注册表 + 适配器声明的能力位。新增一家供应商不再需要回来改这里。
     */
    private boolean supportsRemoteGoodsSync(SupplierItem item) {
        return supplierAdapters.find(item).map(SupplierAdapter::supportsRemoteGoodsSync).orElse(false);
    }

    /**
     * 批次3：原为 5 段 if 逐家写死中文名（卡速售/咔咔云不在其中，回落 "该平台"）。
     * 现改为查注册表取 displayName，仅对「不提供单品查询接口」的 5 家生效，
     * 命中集合与回落文案与原实现完全一致。
     */
    private String platformLabelForManualSupplier(SupplierItem item) {
        return supplierAdapters.find(item)
            .filter(adapter -> !adapter.supportsSingleGoodsQuery())
            .map(SupplierAdapter::displayName)
            .orElse("该平台");
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
        if (!StringUtils.hasText(callbackUrl)) {
            return "";
        }
        String normalized = SupplierCallbackUrlResolver.normalizeCustomUrl(callbackUrl);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("callbackUrl must be a valid http or https URL");
        }
        return normalized;
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
        return userService.requiredUser(id);
    }

    public CardImportResult importCards(Long targetGoodsId, CardImportRequest request) {
        synchronized (cardLock) {
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
    }

    public CardImportResult importCardKindCards(Long targetCardKindId, CardImportRequest request) {
        synchronized (cardLock) {
            if (!catalogService.cardKindsMap().containsKey(targetCardKindId)) {
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
        if (!catalogService.cardKindsMap().containsKey(cardKindId)) {
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

    private Optional<OrderItem> findIdempotentOrder(Long userId, String requestId) {
        String normalizedRequestId = defaultText(requestId, "").trim();
        if (!StringUtils.hasText(normalizedRequestId)) {
            return Optional.empty();
        }
        if (persistentOrderStore != null) {
            return persistentOrderStore.findOrderByRequestId(userId, normalizedRequestId);
        }
        return allOrderSnapshots().stream()
            .filter(order -> Objects.equals(order.userId(), userId))
            .filter(order -> Objects.equals(defaultText(order.requestId(), "").trim(), normalizedRequestId))
            .findFirst();
    }

    private boolean sameOrderRequest(OrderItem order, CreateOrderRequest request, String sourcePlatform, int quantity) {
        return Objects.equals(order.goodsId(), request.goodsId())
            && Objects.equals(order.quantity(), quantity)
            && Objects.equals(storedRechargeAccount(order), normalizedOrderText(legacyRechargeAccount(request)))
            && Objects.equals(normalizedOrderText(order.buyerRemark()), normalizedOrderText(request.buyerRemark()))
            && Objects.equals(normalizeSalePlatform(order.platform()), normalizeSalePlatform(sourcePlatform));
    }

    private String storedRechargeAccount(OrderItem order) {
        String account = normalizedOrderText(order.rechargeAccount());
        if (StringUtils.hasText(account)) {
            return account;
        }
        return normalizedRechargeFields(order.rechargeFields()).values().stream()
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("");
    }

    private String normalizedOrderText(String value) {
        return defaultText(value, "").trim();
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
            legacyRechargeAccount(request),
            normalizedRechargeFields(request.rechargeFields()),
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

    /**
     * 建退款单<b>并把钱退回去</b>（缺陷 A1 的修复落点）。
     *
     * <p>原实现只做了三件事：{@code refunds.put} + 快照落库 + 写操作日志。
     * <b>一分钱都没退</b>。订单被标成 REFUNDED，钱留在平台账上，
     * 用户看到"已退款"但余额不动——这是最严重的资金缺陷。
     *
     * <p>现在退款走 {@link FundsLedgerStore#refundToBalance}，在<b>同一个数据库事务</b>里
     * 完成「写退款记录 + 余额 {@code balance = balance + ?} + 写 CREDIT 流水」。
     * 幂等由 {@code uk_balance_tx_biz (ORDER_REFUND, orderNo)} 保证：
     * 重复调用不会重复退钱，也不会产生第二条退款单。
     */
    private RefundItem createRefund(OrderItem order, String reason) {
        Optional<RefundItem> existing = findExistingRefund(order.orderNo());
        if (existing.isPresent()) {
            RefundItem refund = existing.get();
            if (fundsLedgerEnabled()) {
                // 历史脏数据补救：退款单已存在但从没真正退过钱的订单，
                // 这里会把钱补上（幂等键保证只补一次）。
                settleRefund(refund, reason);
            }
            return refund;
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
        if (fundsLedgerEnabled()) {
            settleRefund(refund, reason);
        } else {
            persistRefundSnapshot(refund);
        }
        refunds.put(refund.refundNo(), refund);
        appendOperation("REFUND_CREATE", "REFUND", refund.refundNo(), reason);
        return refund;
    }

    /** 退款单 + 加回余额 + CREDIT 流水，同一事务；幂等。 */
    private void settleRefund(RefundItem refund, String reason) {
        fundsLedgerStore.refundToBalance(refund, reason);
        userService.usersMap().remove(refund.userId());
    }

    /** 查已存在的退款单：内存优先，持久化模式回查 DB（内存 Map 可能是空的）。 */
    private Optional<RefundItem> findExistingRefund(String orderNo) {
        Optional<RefundItem> inMemory = refunds.values().stream()
            .filter(refund -> Objects.equals(refund.orderNo(), orderNo))
            .findFirst();
        if (inMemory.isPresent()) {
            return inMemory;
        }
        if (persistentOrderStore == null) {
            return Optional.empty();
        }
        try {
            return persistentOrderStore.listRefunds().stream()
                .filter(refund -> Objects.equals(refund.orderNo(), orderNo))
                .findFirst();
        } catch (RuntimeException ex) {
            recordReadFallback("REFUND", defaultText(orderNo, ""), ex);
            return Optional.empty();
        }
    }

    /**
     * 记录支付回调。
     *
     * <p>批次4：改成<b>按幂等键落库</b>。原实现每次重放都无条件插一行，
     * 10 次重放留 10 行；006 迁移建好的 {@code idempotency_key} 列从未被写入，
     * 而 MySQL 唯一索引允许多个 NULL，于是 {@code uk_payment_callback_idem} 形同虚设。
     *
     * <p>现在幂等键由 provider + 支付单号 + 订单号 + 回调状态 + 渠道流水号 计算，
     * 用 {@code INSERT IGNORE} 交给唯一索引去重，重放只留第一行。
     */
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
        if (fundsLedgerEnabled()) {
            fundsLedgerStore.recordCallbackOnce(log);
            return;
        }
        persistPaymentCallbackLog(log);
    }

    private void persistOrderSnapshot(OrderItem order) {
        persistOrderSnapshot(order, null);
    }

    private void persistOrderSnapshot(OrderItem order, BigDecimal externalMaxAmount) {
        if (persistentOrderStore == null || order == null) {
            return;
        }
        try {
            persistentOrderStore.saveOrderSnapshot(order, externalMaxAmount);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ORDER", order.orderNo(), persistenceErrorMessage(ex));
            throw ex;
        }
    }

    private void persistProcurementCost(String orderNo, BigDecimal costAmount) {
        if (persistentOrderStore == null || !StringUtils.hasText(orderNo) || costAmount == null) {
            return;
        }
        try {
            persistentOrderStore.saveCostAmount(orderNo, costAmount);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ORDER", orderNo, persistenceErrorMessage(ex));
            throw ex;
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
            throw ex;
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
            throw ex;
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
            throw ex;
        }
    }






    private void persistUserSnapshot(UserItem user) {
        userService.persistUserSnapshot(user);
    }









    private void deletePersistentCardsByGoods(Long goodsId) {
        if (persistentOrderStore == null || goodsId == null) {
            return;
        }
        try {
            persistentOrderStore.deleteCardsByGoods(goodsId);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CARD", String.valueOf(goodsId), persistenceErrorMessage(ex));
            throw ex;
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



    private void persistPaymentChannels() {
        try {
            List<Map<String, Object>> payload = listPaymentChannels().stream()
                .map(this::paymentChannelPayload)
                .toList();
            configService.savePaymentChannelsJson(OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "PAYMENT_CHANNEL", "LIST", ex.getMessage());
            throw new IllegalStateException("payment channel serialization failed", ex);
        }
    }




    // 批次8D / 任务D1：短信登录设置 / 人机验证设置 / 员工账号三份 DB 镜像的
    // 写入与读回都随实现搬到了 AuthService，仓储侧不再保留副本。










    private void loadPaymentChannels() {
        if (configPersistenceStore == null) {
            seedPaymentChannels(false);
            return;
        }
        try {
            String raw = configService.paymentChannelsJson();
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
            recordReadFallback("PAYMENT_CHANNEL", "LIST", ex);
            seedPaymentChannels(false);
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
            recordReadFallback("ORDER", "LIST", ex);
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
            recordReadFallback("ORDER", orderNo, ex);
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
            recordReadFallback("PAYMENT", "LIST", ex);
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
            recordReadFallback("PAYMENT_CALLBACK", "LIST", ex);
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
            recordReadFallback("REFUND", "LIST", ex);
            return Optional.empty();
        }
    }



    /**
     * 记录一次「数据库读失败，回落到内存」。
     *
     * <h2>为什么必须打日志，而不是只写一条审计行</h2>
     * 原来这 16 处只调 {@code appendOperation(...)}，异常对象整个被吞掉：
     * 接口照样返回 200，列表只是变空或变旧，stdout 里一个字都没有。
     * 批次8C 就踩了这个坑——分页 SQL 的 ESCAPE 转义写错导致语句语法报错，
     * 但对外表现只是「订单列表恒为空」，排查时 grep 日志找不到任何异常，
     * 一度误判成「SQL 执行成功但没匹配到数据」，白花一轮时间。
     *
     * <p>因此补一条带异常栈的 WARN。审计行保持原样（动作名、字段、参数顺序都不变），
     * 运维在后台仍看得到 PERSISTENCE_READ_FALLBACK；日志则给出可定位的根因。
     *
     * <p>入参收 {@link Exception} 而非 RuntimeException：有 4 处 catch 的是
     * {@code RuntimeException | JsonProcessingException}，静态类型是 Exception。
     */
    private void recordReadFallback(String targetType, String targetId, Exception ex) {
        LOG.warn("数据库读失败，已回落到内存数据：target={}/{}；接口不会报错，但返回的数据可能为空或过期",
            targetType, targetId, ex);
        appendOperation("PERSISTENCE_READ_FALLBACK", targetType, targetId, persistenceErrorMessage(ex));
    }

    private String persistenceErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private Long allocateIncrementingId(AtomicLong sequence, long maxExistingId) {
        long id = Math.max(sequence.get() + 1, maxExistingId + 1);
        sequence.set(id);
        return id;
    }






    private long maxSupplierId() {
        long max = suppliers.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<SupplierItem>> persistent = persistentSuppliers();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(SupplierItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
        }
        return max;
    }









    private List<UserItem> allUserSnapshots() {
        return userService.allUserSnapshots();
    }


    private Optional<UserItem> findUserSnapshot(Long id) {
        return userService.findUserSnapshot(id);
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

    /**
     * 批次6 / 任务B：审计写入的<b>转发壳</b>。
     *
     * <p>刻意保留同名同签名的私有方法：仓储内 88 处调用点一个都不用改，
     * 这次重构在那 88 条业务分支上不引入任何行为差异。
     * 真正的职责（id 分配、内存态、DB 镜像、失败不递归）在 {@link AuditService}。
     */
    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        auditService.appendOperation(action, resourceType, resourceId, remark);
    }

    private boolean isProductMonitorChannel(GoodsChannelItem channel) {
        GoodsItem item = findGoodsSnapshot(channel.goodsId()).orElse(null);
        return item != null
            && !Boolean.FALSE.equals(item.monitoringEnabled())
            && "ENABLED".equals(channel.status())
            && supportsRealtimeProductMonitor(channel.supplierId());
    }

    private boolean isProductMonitorChannel(GoodsChannelItem channel, Map<Long, GoodsItem> goodsById) {
        if (channel == null || goodsById == null) {
            return false;
        }
        GoodsItem item = goodsById.get(channel.goodsId());
        return item != null
            && !Boolean.FALSE.equals(item.monitoringEnabled())
            && "ENABLED".equals(channel.status())
            && supportsRealtimeProductMonitor(channel.supplierId());
    }

    private boolean supportsRealtimeProductMonitor(Long supplierId) {
        if (supplierId == null) {
            return false;
        }
        SupplierItem supplier = findSupplierSnapshot(supplierId).orElse(null);
        return supplier != null
            && "ENABLED".equals(supplier.status())
            && supportsRemoteGoodsSync(supplier)
            && !isPlaceholderBaseUrl(supplier.baseUrl());
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
            normalizePaymentChannelConfig(settingConfig(payload, "config")),
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
        putEncryptedConfig(payload, "config", item.config());
        payload.put("remark", item.remark());
        payload.put("createdAt", item.createdAt() == null ? "" : item.createdAt().toString());
        payload.put("updatedAt", item.updatedAt() == null ? "" : item.updatedAt().toString());
        return payload;
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






    /**
     * 可售卡密数量（缺陷 A2/A3 的修复落点）。
     *
     * <p>原实现只数内存 {@code cards} Map。而这个 Map 在持久化模式下<b>从不被填充</b>
     * （构造器里 {@code if (persistenceEnabled()) return;} 直接跳过所有 seed），
     * 于是库里 600 张卡、内存 0 张，可售数恒为 0，CARD 商品根本下不了单。
     *
     * <p>修法是让 DB 成为唯一事实来源：持久化模式直接
     * {@code SELECT COUNT(*) FROM cards WHERE status='UNSOLD'}。
     * 注意状态词表不同——内存用 AVAILABLE，DB 用 UNSOLD，这也是当初两条路径对不上的原因之一。
     */
    private int availableCardCount(Long targetGoodsId) {
        if (fundsLedgerEnabled()) {
            return fundsLedgerStore.availableCardCount(targetGoodsId, null);
        }
        return (int) cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), targetGoodsId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .count();
    }

    private int availableCardKindCardCount(Long targetCardKindId) {
        if (fundsLedgerEnabled()) {
            return fundsLedgerStore.availableCardCount(null, targetCardKindId);
        }
        return (int) cards.values().stream()
            .filter(card -> Objects.equals(card.cardKindId(), targetCardKindId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .count();
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
        body.put("goodsid", SupplierGoodsId.of(supplierGoodsId));
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









    private void validateGoodsGroupAccess(UserItem user, GoodsItem item) {
        userService.validateGoodsGroupAccess(user, item);
    }



    private void validateOrderPermission(UserItem user) {
        userService.validateOrderPermission(user);
    }


    private void validatePriceLimitPermission(UserItem user, GoodsItem item) {
        userService.validatePriceLimitPermission(user, item);
    }




    private UserItem withGroupName(UserItem user) {
        return userService.withGroupName(user);
    }


    private UserItem withUserBalance(UserItem user, BigDecimal balance) {
        return userService.withUserBalance(user, balance);
    }


    private UserItem withUserLastLoginAt(UserItem user, OffsetDateTime lastLoginAt) {
        return userService.withUserLastLoginAt(user, lastLoginAt);
    }


    private UserItem createUserFromAccount(String account, String username) {
        return userService.createUserFromAccount(account, username);
    }

    private String groupName(Long groupId) {
        return userService.groupName(groupId);
    }


    private String normalizeRegistrationType(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (List.of("MOBILE", "EMAIL", "FREE").contains(normalized)) {
            return normalized;
        }
        return "MOBILE";
    }


    private Long validDefaultUserGroupId(Long groupId) {
        return userService.validDefaultUserGroupId(groupId);
    }


    private void validateRegistration(String account) {
        userService.validateRegistration(account);
    }


















    private void validateRechargeFields(GoodsItem item, CreateOrderRequest request) {
        if (!Boolean.TRUE.equals(item.requireRechargeAccount())) {
            return;
        }
        List<RechargeFieldItem> selectedFields = selectedRechargeFields(item);
        Map<String, String> submittedFields = normalizedRechargeFields(request == null ? null : request.rechargeFields());
        if (!submittedFields.isEmpty()) {
            if (submittedFields.values().stream().noneMatch(StringUtils::hasText)) {
                throw new IllegalArgumentException("请先填写充值账号");
            }
            Set<String> allowedCodes = selectedFields.stream().map(RechargeFieldItem::code).collect(java.util.stream.Collectors.toSet());
            if (!allowedCodes.containsAll(submittedFields.keySet())) {
                throw new IllegalArgumentException("充值字段与商品配置不匹配");
            }
            boolean hasAnyRequired = selectedFields.stream().anyMatch(f -> Boolean.TRUE.equals(f.required()));
            boolean anyRequiredFilled = selectedFields.stream()
                .filter(f -> Boolean.TRUE.equals(f.required()))
                .anyMatch(f -> StringUtils.hasText(submittedFields.getOrDefault(f.code(), "")));
            if (hasAnyRequired && !anyRequiredFilled) {
                String labels = selectedFields.stream()
                    .filter(f -> Boolean.TRUE.equals(f.required()))
                    .map(RechargeFieldItem::label)
                    .distinct()
                    .reduce((a, b) -> a + " / " + b)
                    .orElse("充值账号");
                throw new IllegalArgumentException("请填写充值账号（" + labels + " 任填一项）");
            }
            for (RechargeFieldItem field : selectedFields) {
                String value = submittedFields.getOrDefault(field.code(), "");
                if (StringUtils.hasText(value) && !rechargeAccountMatches(field.inputType(), value)) {
                    throw new IllegalArgumentException("请输入正确的" + field.label());
                }
            }
            return;
        }

        String account = defaultText(request == null ? null : request.rechargeAccount(), "").trim();
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请先填写充值账号");
        }
        if (selectedFields.isEmpty()) {
            return;
        }

        boolean matched = selectedFields.stream().anyMatch(field -> rechargeAccountMatches(field.inputType(), account));
        if (!matched) {
            String labels = selectedFields.stream().map(RechargeFieldItem::label).distinct().reduce((left, right) -> left + " / " + right).orElse("充值账号");
            throw new IllegalArgumentException("请输入正确的" + labels);
        }
    }

    private CreateOrderRequest normalizeRechargeRequest(GoodsItem item, CreateOrderRequest request) {
        if (request == null || !Boolean.TRUE.equals(item.requireRechargeAccount())) {
            return request;
        }
        List<RechargeFieldItem> selectedFields = selectedRechargeFields(item);
        Map<String, String> submittedFields = normalizedRechargeFields(request.rechargeFields());
        Set<String> allowedCodes = selectedFields.stream()
            .map(RechargeFieldItem::code)
            .collect(java.util.stream.Collectors.toSet());
        if (!allowedCodes.containsAll(submittedFields.keySet())) {
            throw new IllegalArgumentException("充值字段与商品配置不匹配");
        }

        String genericAccount = normalizedOrderText(request.rechargeAccount());
        List<String> values = java.util.stream.Stream.concat(
                submittedFields.values().stream().filter(StringUtils::hasText),
                StringUtils.hasText(genericAccount) ? java.util.stream.Stream.of(genericAccount) : java.util.stream.Stream.empty()
            )
            .distinct()
            .toList();
        if (values.isEmpty()) {
            return rechargeRequest(request, "", Map.of());
        }
        if (selectedFields.isEmpty()) {
            if (values.size() > 1) {
                throw new IllegalArgumentException("充值账号字段冲突");
            }
            return rechargeRequest(request, values.getFirst(), Map.of());
        }

        Map<String, RechargeFieldItem> canonicalFields = new LinkedHashMap<>();
        for (String value : values) {
            RechargeFieldItem canonical = selectedFields.stream()
                .filter(field -> value.equals(submittedFields.get(field.code())))
                .filter(field -> rechargeAccountMatches(field.inputType(), value))
                .findFirst()
                .orElseGet(() -> selectedFields.stream()
                    .filter(field -> rechargeAccountMatches(field.inputType(), value))
                    .findFirst()
                    .orElse(null));
            if (canonical == null) {
                RechargeFieldItem submitted = selectedFields.stream()
                    .filter(field -> value.equals(submittedFields.get(field.code())))
                    .findFirst()
                    .orElse(null);
                String label = submitted == null
                    ? selectedFields.stream().map(RechargeFieldItem::label).distinct()
                        .reduce((left, right) -> left + " / " + right).orElse("充值账号")
                    : submitted.label();
                throw new IllegalArgumentException("请输入正确的" + label);
            }
            canonicalFields.put(value, canonical);
        }
        if (canonicalFields.size() > 1) {
            throw new IllegalArgumentException("充值账号字段冲突");
        }

        String account = values.getFirst();
        RechargeFieldItem field = canonicalFields.get(account);
        return rechargeRequest(request, account, Map.of(field.code(), account));
    }

    private List<RechargeFieldItem> selectedRechargeFields(GoodsItem item) {
        return normalizeTextList(item.accountTypes()).stream()
            .map(this::rechargeFieldByCode)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(RechargeFieldItem::enabled)
            .toList();
    }

    private CreateOrderRequest rechargeRequest(
        CreateOrderRequest request,
        String rechargeAccount,
        Map<String, String> rechargeFields
    ) {
        return new CreateOrderRequest(
            request.goodsId(), request.quantity(), rechargeAccount, request.buyerRemark(), request.requestId(),
            request.terminal(), rechargeFields
        );
    }

    private Map<String, String> normalizedRechargeFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        fields.forEach((code, value) -> {
            String normalizedCode = normalizeRechargeFieldCode(code);
            if (StringUtils.hasText(normalizedCode)) {
                normalized.put(normalizedCode, defaultText(value, "").trim());
            }
        });
        return Map.copyOf(normalized);
    }

    private String legacyRechargeAccount(CreateOrderRequest request) {
        String legacyValue = defaultText(request == null ? null : request.rechargeAccount(), "").trim();
        if (StringUtils.hasText(legacyValue)) {
            return legacyValue;
        }
        return normalizedRechargeFields(request == null ? null : request.rechargeFields()).values().stream()
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("");
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

    private void sendGenericSms(Map<String, String> config, String mobile, String code, String content) {
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

    private void sendTencentSms(Map<String, String> config, String mobile, String code) {
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

    private void verifyTencentCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp) {
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

    private void verifyTurnstileCaptcha(Map<String, String> config, String token, String clientIp) {
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

    private void verifyGenericCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp) {
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

    private void sendAliyunSms(Map<String, String> config, String mobile, String code) {
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

    private void putEncryptedConfig(Map<String, Object> payload, String key, Map<String, String> config) {
        Map<String, String> normalized = normalizeSmsConfig(config);
        Map<String, String> publicValues = new LinkedHashMap<>();
        Map<String, Map<String, String>> encryptedValues = new LinkedHashMap<>();
        normalized.forEach((configKey, value) -> {
            if (isSensitiveConfigKey(configKey) && StringUtils.hasText(value)) {
                if (configPersistenceStore == null) {
                    throw new IllegalStateException("secret persistence is unavailable");
                }
                encryptedValues.put(configKey, configService.encryptSecretForSetting(value));
            } else {
                publicValues.put(configKey, value);
            }
        });
        payload.put(key, Map.copyOf(publicValues));
        if (!encryptedValues.isEmpty()) {
            payload.put(key + "Secrets", Map.copyOf(encryptedValues));
        }
    }

    private Map<String, String> settingConfig(Map<String, Object> payload, String key) {
        Map<String, String> values = new LinkedHashMap<>(stringMap(payload.get(key)));
        Object rawSecrets = payload.get(key + "Secrets");
        if (rawSecrets instanceof Map<?, ?> encryptedValues) {
            encryptedValues.forEach((rawKey, rawValue) -> {
                String configKey = normalizePaymentConfigKey(String.valueOf(rawKey));
                if (!StringUtils.hasText(configKey) || !(rawValue instanceof Map<?, ?> encrypted)) {
                    return;
                }
                String ciphertext = defaultText(encrypted.get("ciphertext"), "");
                String nonce = defaultText(encrypted.get("nonce"), "");
                if (!StringUtils.hasText(ciphertext) || !StringUtils.hasText(nonce) || configPersistenceStore == null) {
                    throw new IllegalStateException("encrypted setting is incomplete: " + key + "." + configKey);
                }
                values.put(configKey, configService.decryptSecretFromSetting(ciphertext, nonce));
            });
        }
        return normalizeSmsConfig(values);
    }

    /**
     * 该配置键是否属敏感项（决定加密入库还是明文入库）。
     *
     * <p><b>新增任何密钥类配置时必须确认这里能匹配上，否则会明文落库。</b>
     * 已发生过一次：Altcha 的 HMAC 密钥最初命名为 {@code hmac_key}，
     * 不含 secret / password / token 等任何既有关键词，于是被当作公开配置
     * 明文写进 {@code config_public} 列。该密钥是签发 PoW 挑战的唯一凭据，
     * 泄露即等于人机验证可被批量绕过。
     *
     * <p>{@code public_key} 结尾的键必须排除：支付宝公钥等本就是公开值，
     * 加密它们只会让验签路径多一次无谓解密。
     */
    private boolean isSensitiveConfigKey(String key) {
        String normalized = normalizePaymentConfigKey(key);
        if (normalized.endsWith("public_key")) {
            return false;
        }
        return normalized.contains("secret")
            || normalized.contains("private_key")
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("api_key")
            || normalized.matches("api_.+_key")
            || normalized.contains("access_key")
            || normalized.contains("hmac");
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
        userService.seedUserGroups();
    }


    private void seedUsers() {
        userService.seedUsers();
    }


    private void seedMemberCredentials() {
        userService.seedMemberCredentials();
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
