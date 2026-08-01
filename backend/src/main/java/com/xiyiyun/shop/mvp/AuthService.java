package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.security.LoginAttemptGuard;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * 批次8D / 任务D1：鉴权域（会员登录 / 管理端登录 / 令牌 / 口令 / 短信验证码 /
 * 人机验证 / 管理员与员工账号）的唯一出口。
 *
 * <h2>为什么单独一层</h2>
 * 这些状态原先和订单、卡密、商品、上游 HTTP 挤在 {@code InMemoryShopRepository} 里：
 * 令牌表、口令哈希表、验证码表、滑块票据、员工账号、短信与验证码设置各自的内存态、
 * id 序列、DB 镜像、读降级散落在近 8000 行的四个不同区段。本类把它们收口 ——
 * 仓储不再直接持有 {@code userTokens} / {@code adminTokens} / {@code userPasswordHashes} /
 * {@code adminStaff} / {@code smsVerificationCodes} / {@code smsLoginSetting} /
 * {@code captchaSetting} 任何一张表或任何一份设置。
 *
 * <h2>批次5 / B2 的四条登录加固红线，逐字保留</h2>
 * <ol>
 *   <li><b>账号 + IP 双维度失败计数与阶梯锁定</b>：全部经 {@link #loginGuard()} 取到的
 *       {@link LoginAttemptGuard}，调用点、调用顺序、维度参数（terminal/account/clientIp）
 *       与重构前一致；任何口令比对之前先 {@code assertCanAttempt}。</li>
 *   <li><b>统一登录失败文案</b> {@link #UNIFIED_LOGIN_FAILURE_MESSAGE}：账号不存在、
 *       无口令哈希、口令错误三条路径共用同一句，避免账号存在性泄露。</li>
 *   <li><b>时间侧信道补偿</b> {@link #DUMMY_BCRYPT_HASH}（cost=10）+
 *       {@link #burnEquivalentPasswordWork}：账号不存在时照样付一次等价 BCrypt 开销。</li>
 *   <li><b>「员工账号已停用」必须在口令校验通过之后才提示</b>：见
 *       {@link #loginAdmin} 里的先后次序，提前判断会把接口变成账号存在性探针。</li>
 * </ol>
 *
 * <h2>为什么依赖靠 {@link AuthGateway} 反向注入</h2>
 * 鉴权域还欠仓储三类东西：{@code loginAttemptGuard} / {@code adminCaptchaRequired} 的
 * <b>当前值</b>（仓储上的字段注入，测试还会运行期替换，只能每次经 gateway 现取）、
 * 短信与人机验证的出网传输层（签名工具与 HttpClient 封装被支付等非登录代码共用）、
 * 以及会员域的读写（批次7C 已归 {@link UserService}）。
 *
 * <h2>为什么放在 mvp 包</h2>
 * {@link AdminProfile} / {@link AdminStaffItem} / {@link SmsLoginSettingItem} /
 * {@link CaptchaSettingItem} / {@link SmsVerificationCode} 等 DTO 全在 mvp，
 * 其中若干是 package-private。沿用批次2/3/6/7B/7C 的判断：接口与新服务优先留在 mvp，
 * 不为了教条的包隔离去搬迁几十个 DTO。
 */
public class AuthService {
    private static final PasswordEncoder ADMIN_PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final Duration USER_TOKEN_TTL = Duration.ofDays(30);
    private static final Duration ADMIN_TOKEN_TTL = Duration.ofHours(12);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> ALL_ADMIN_PERMISSIONS = List.of(
        "dashboard:read",
        "goods:manage",
        "orders:manage",
        "users:manage",
        "settings:manage",
        "staff:manage"
    );
    /**
     * 统一的登录失败文案（批次5 / B2）。
     *
     * <p>"账号不存在"和"密码错误"必须返回<b>同一句话</b>，否则攻击者可据此枚举有效账号。
     */
    private static final String UNIFIED_LOGIN_FAILURE_MESSAGE = "账号或密码不正确";
    /**
     * 账号不存在时用于消耗等价 BCrypt 开销的固定哈希（批次5 / B2）。
     *
     * <p>这不是任何账号的口令，只是一个 cost=10 的合法 BCrypt 串。作用是让
     * "账号不存在"路径与"密码错误"路径的响应时间处于同一量级，堵掉时间侧信道。
     */
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthGateway gateway;
    private final AuditService auditService;
    private final ConfigService configService;
    private final ConfigPersistenceStore configPersistenceStore;
    private final RedisSecurityStateStore securityStateStore;
    private final AltchaCaptchaService altchaService;

    private final Map<String, SmsVerificationCode> smsVerificationCodes = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> sliderTokens = new ConcurrentHashMap<>();
    private final Map<Long, String> userPasswordHashes = new ConcurrentHashMap<>();
    private final Map<String, Long> userTokens = new ConcurrentHashMap<>();
    private final Map<String, AdminProfile> adminTokens = new ConcurrentHashMap<>();
    private final Map<Long, AdminStaffItem> adminStaff = new ConcurrentHashMap<>();
    private final Map<Long, String> adminStaffPasswordHashes = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> userTokenExpiresAt = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> adminTokenExpiresAt = new ConcurrentHashMap<>();
    private final AtomicLong adminStaffId = new AtomicLong(1000);
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
        Map.of(),
        Map.of("hmac_key", "")
    );
    /** loginAttemptGuard 为 null 时的进程内兜底实例。 */
    private volatile LoginAttemptGuard fallbackLoginGuard;
    private volatile String adminUsername;
    private volatile String adminPasswordBcrypt;
    private volatile String adminNickname;

    AuthService(
        AuthGateway gateway,
        AuditService auditService,
        ConfigService configService,
        ConfigPersistenceStore configPersistenceStore,
        RedisSecurityStateStore securityStateStore,
        AltchaCaptchaService altchaService,
        String adminUsername,
        String adminPasswordBcrypt,
        String adminNickname
    ) {
        this.gateway = gateway;
        this.auditService = auditService;
        this.configService = configService;
        this.configPersistenceStore = configPersistenceStore;
        this.securityStateStore = securityStateStore;
        this.altchaService = altchaService;
        this.adminUsername = adminUsername;
        this.adminPasswordBcrypt = adminPasswordBcrypt;
        this.adminNickname = adminNickname;
    }

    // ------------------------------------------------------------------ 转发到 AuditService（与仓储同名同语义）

    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        auditService.appendOperation(action, resourceType, resourceId, remark);
    }

    // ------------------------------------------------------------------ 短信登录 / 人机验证设置

    SmsLoginSettingItem smsLoginSetting() {
        return smsLoginSetting;
    }

    CaptchaSettingItem captchaSetting() {
        return captchaSetting;
    }

    CaptchaChallengeItem captchaChallenge(String terminal) {
        String cleanTerminal = gateway.normalizeTerminal(terminal);
        boolean required = isCaptchaRequired(cleanTerminal);
        String provider = normalizeCaptchaProvider(captchaSetting.provider());
        Map<String, String> config = switch (provider) {
            case "TURNSTILE" -> captchaSetting.turnstileConfig();
            case "ALTCHA" -> captchaSetting.altchaConfig();
            default -> captchaSetting.tencentConfig();
        };
        return new CaptchaChallengeItem(
            required,
            provider,
            required ? defaultText("TURNSTILE".equals(provider) ? config.get("site_key") : config.get("captcha_app_id"), "") : "",
            defaultText(config.get("scene"), "login")
        );
    }

    /** 生成 Altcha PoW 挑战，由前端 altcha-widget 直接消费。 */
    String altchaChallenge() {
        return altchaService.generateChallengeJson(
            defaultText(captchaSetting.altchaConfig().get("hmac_key"), "")
        );
    }

    SmsLoginSettingItem updateSmsLoginSetting(SmsLoginSettingRequest request) {
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
            gateway.normalizeSmsConfig(request.genericConfig() == null ? smsLoginSetting.genericConfig() : request.genericConfig()),
            gateway.normalizeSmsConfig(request.tencentConfig() == null ? smsLoginSetting.tencentConfig() : request.tencentConfig()),
            gateway.normalizeSmsConfig(request.aliyunConfig() == null ? smsLoginSetting.aliyunConfig() : request.aliyunConfig())
        );
        persistSmsLoginSetting();
        return smsLoginSetting;
    }

    CaptchaSettingItem updateCaptchaSetting(CaptchaSettingRequest request) {
        if (request == null) {
            return captchaSetting;
        }
        CaptchaSettingItem next = captchaSettingFromRequest(request);
        validateCaptchaSetting(next);
        captchaSetting = next;
        persistCaptchaSetting();
        return captchaSetting;
    }

    String testCaptchaSetting(CaptchaSettingRequest request) {
        CaptchaSettingItem setting = request == null ? captchaSetting : captchaSettingFromRequest(request);
        validateCaptchaSetting(setting);
        if (!setting.enabled()) {
            return "人机验证总开关未开启，当前不会触发校验。";
        }
        return switch (normalizeCaptchaProvider(setting.provider())) {
            case "GENERIC" -> gateway.testGenericCaptchaSetting(setting.genericConfig());
            case "TURNSTILE" -> gateway.testTurnstileCaptchaSetting(setting.turnstileConfig());
            // Altcha 是自托管的，没有可探活的第三方端点：能签出挑战就等于配置可用。
            case "ALTCHA" -> altchaService.testSetting(setting.altchaConfig());
            default -> gateway.testTencentCaptchaSetting(setting.tencentConfig());
        };
    }

    private CaptchaSettingItem captchaSettingFromRequest(CaptchaSettingRequest request) {
        return new CaptchaSettingItem(
            request.enabled() == null ? captchaSetting.enabled() : request.enabled(),
            request.adminLoginEnabled() == null ? captchaSetting.adminLoginEnabled() : request.adminLoginEnabled(),
            request.h5LoginEnabled() == null ? captchaSetting.h5LoginEnabled() : request.h5LoginEnabled(),
            request.webLoginEnabled() == null ? captchaSetting.webLoginEnabled() : request.webLoginEnabled(),
            normalizeCaptchaProvider(defaultText(request.provider(), captchaSetting.provider())),
            gateway.normalizeSmsConfig(request.tencentConfig() == null ? captchaSetting.tencentConfig() : request.tencentConfig()),
            gateway.normalizeSmsConfig(request.turnstileConfig() == null ? captchaSetting.turnstileConfig() : request.turnstileConfig()),
            gateway.normalizeSmsConfig(request.genericConfig() == null ? captchaSetting.genericConfig() : request.genericConfig()),
            gateway.normalizeSmsConfig(request.altchaConfig() == null ? captchaSetting.altchaConfig() : request.altchaConfig())
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
        if ("ALTCHA".equals(provider)) {
            requireConfig(setting.altchaConfig(), "hmac_key", "Altcha HMAC 密钥");
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

    // ------------------------------------------------------------------ 验证码下发与滑块票据

    String sendAdminLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
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

    String sendUserLoginSmsCode(SendSmsCodeRequest request, String clientIp) {
        String terminal = gateway.normalizeTerminal(request == null ? "" : request.terminal());
        String mode = normalize(defaultText(request == null ? "" : request.mode(), "login"));
        verifyHumanCaptchaIfRequired(terminal, request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        String mobile = normalize(request == null ? "" : request.account());
        if ("register".equals(mode)) {
            gateway.validateRegistration(mobile);
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

    String createSliderToken(String terminal) {
        String token = "slider_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(5);
        sliderTokens.put(token, expiresAt);
        if (securityStateStore != null) {
            securityStateStore.storeSliderToken(token, Duration.between(OffsetDateTime.now(), expiresAt));
        }
        return token;
    }

    // ------------------------------------------------------------------ 会员登录 / 注册 / 找回密码

    /**
     * 会员登录（批次5 / B2 加固）。
     *
     * <p>改了三件事：
     * <ol>
     *   <li><b>前置节流</b>：任何密码比对之前先过 {@link LoginAttemptGuard}，
     *       锁定期内直接拒——即使密码正确也不放行，且不付 BCrypt 开销。</li>
     *   <li><b>消除用户名枚举</b>：原来"账号不存在"抛 {@code 账号不存在，请先注册}、
     *       密码错抛 {@code 账号或密码不正确}，两条文案不同，攻击者据此可枚举有效账号。
     *       现在统一为 {@link #UNIFIED_LOGIN_FAILURE_MESSAGE}。</li>
     *   <li><b>消除时间侧信道</b>：账号不存在时不再直接返回，而是拿
     *       {@link #DUMMY_BCRYPT_HASH} 走一次<b>等价开销</b>的 BCrypt 比对再失败，
     *       否则"存在的账号慢、不存在的账号快"本身就是枚举通道。</li>
     * </ol>
     */
    AuthSession<UserItem> loginUser(LoginRequest request, String clientIp, boolean verifyCaptcha) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("account is required");
        }
        String terminal = gateway.normalizeTerminal(request == null ? "" : request.terminal());
        loginGuard().assertCanAttempt(terminal, account, clientIp);
        if (verifyCaptcha) {
            verifyHumanCaptchaIfRequired(terminal, request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        }
        boolean smsRequired = isUserSmsLoginRequired(terminal);
        Optional<UserItem> matchedUser = gateway.allUserSnapshots().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account)
                || Objects.equals(normalize(item.email()), account)
                || (StringUtils.hasText(item.username()) && Objects.equals(item.username(), account)))
            .findFirst();
        boolean hasPassword = StringUtils.hasText(defaultText(request == null ? "" : request.password(), ""));
        if (smsRequired) {
            verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        }
        if (matchedUser.isEmpty()) {
            // 账号不存在：先跑一次等价开销的哈希比对，再以统一文案失败
            burnEquivalentPasswordWork(request);
            loginGuard().recordFailure(terminal, account);
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
        UserItem user = matchedUser.get();
        if (!smsRequired || hasPassword) {
            try {
                verifyUserPassword(user, request);
            } catch (IllegalArgumentException ex) {
                loginGuard().recordFailure(terminal, account);
                throw ex;
            }
        }
        loginGuard().recordSuccess(terminal, account);
        UserItem next = gateway.withUserLastLoginAt(user, OffsetDateTime.now());
        gateway.putUser(next);
        gateway.persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        return new AuthSession<>(token, gateway.withGroupName(next));
    }

    /**
     * 账号不存在时的等价开销哈希比对（批次5 / B2）。
     *
     * <p>只为消除时间差，结果被丢弃。{@link #DUMMY_BCRYPT_HASH} 的 cost 与真实用户口令一致（10），
     * 因此"账号不存在"与"密码错误"两条路径的 CPU 开销量级相同。
     */
    private void burnEquivalentPasswordWork(LoginRequest request) {
        burnEquivalentPasswordWork(request == null ? "" : defaultText(request.password(), request.code()));
    }

    /**
     * 账号不存在时消耗一次等价开销的 BCrypt 比对。
     *
     * <p>{@link #DUMMY_BCRYPT_HASH} 的 cost 与真实口令哈希一致（10），
     * 因此「用户不存在」与「口令错误」两条路径的响应时间量级相同，
     * 时间差不会退化成账号枚举侧信道。
     */
    private void burnEquivalentPasswordWork(String password) {
        try {
            ADMIN_PASSWORD_ENCODER.matches(defaultText(password, ""), DUMMY_BCRYPT_HASH);
        } catch (RuntimeException ignored) {
            // 比对结果无意义，异常也不影响统一失败文案
        }
    }

    /**
     * 返回可用的登录节流器：优先容器注入，纯内存单测下惰性建一个进程内实例。
     *
     * <p>注入实例每次经 {@link AuthGateway#loginAttemptGuard()} <b>现取</b>，
     * 不在构造时缓存 —— 测试会用 {@code replaceLoginAttemptGuardForTest} 运行期替换。
     */
    private LoginAttemptGuard loginGuard() {
        LoginAttemptGuard injected = gateway.loginAttemptGuard();
        if (injected != null) {
            return injected;
        }
        LoginAttemptGuard current = fallbackLoginGuard;
        if (current == null) {
            synchronized (this) {
                if (fallbackLoginGuard == null) {
                    fallbackLoginGuard = LoginAttemptGuard.withDefaults(securityStateStore);
                }
                current = fallbackLoginGuard;
            }
        }
        return current;
    }

    AuthSession<UserItem> authenticateUser(UserAuthRequest request, String clientIp) {
        String mode = normalize(defaultText(request == null ? "" : request.mode(), "login"));
        String terminal = gateway.normalizeTerminal(request == null ? "" : request.terminal());
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

    UserItem changeUserPassword(Long userId, PasswordChangeRequest request) {
        UserItem user = gateway.requiredUser(userId);
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
        if (!StringUtils.hasText(currentHash)) {
            currentHash = persistentUserPasswordHash(userId);
        }
        if (!StringUtils.hasText(currentHash) || !ADMIN_PASSWORD_ENCODER.matches(currentPassword, currentHash)) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        String nextHash = ADMIN_PASSWORD_ENCODER.encode(newPassword);
        userPasswordHashes.put(userId, nextHash);
        configService.saveUserPasswordHash(userId, nextHash);
        invalidateUserTokens(userId);
        appendOperation("USER_PASSWORD_CHANGE", "USER", String.valueOf(userId), "会员修改登录密码");
        return gateway.withGroupName(user);
    }

    private AuthSession<UserItem> registerUser(UserAuthRequest request, String terminal, String account) {
        if (gateway.allUserSnapshots().stream().anyMatch(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))) {
            throw new IllegalStateException("账号已存在，请直接登录");
        }
        gateway.validateRegistration(account);
        if (isRegistrationSmsCodeRequired()) {
            verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        }
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (StringUtils.hasText(password)) {
            validateNewPassword(password, confirmPassword);
        }
        String username = defaultText(request == null ? "" : request.username(), "").trim();
        if (!username.isEmpty()) {
            if (!username.matches("^[a-z0-9_-]{1,12}$")) {
                throw new IllegalArgumentException("用户名只能包含小写字母、数字、下划线和连字符，且不超过 12 个字符");
            }
            if (gateway.allUserSnapshots().stream().anyMatch(item -> username.equals(item.username()))) {
                throw new IllegalStateException("用户名已被占用，请换一个");
            }
        }
        UserItem user = gateway.createUserFromAccount(account, username.isEmpty() ? null : username);
        if (StringUtils.hasText(password)) {
            userPasswordHashes.put(user.id(), ADMIN_PASSWORD_ENCODER.encode(password));
            configService.saveUserPasswordHash(user.id(), userPasswordHashes.get(user.id()));
        }
        UserItem next = gateway.withUserLastLoginAt(user, OffsetDateTime.now());
        gateway.putUser(next);
        gateway.persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        appendOperation("USER_REGISTER", "USER", String.valueOf(next.id()), terminal + ":" + account);
        return new AuthSession<>(token, gateway.withGroupName(next));
    }

    private AuthSession<UserItem> resetUserPassword(UserAuthRequest request, String terminal, String account) {
        UserItem user = gateway.allUserSnapshots().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        verifyLoginSmsCode(verificationKey("USER_LOGIN", terminal, account), request == null ? "" : request.code());
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        validateNewPassword(password, confirmPassword);
        String nextHash = ADMIN_PASSWORD_ENCODER.encode(password);
        userPasswordHashes.put(user.id(), nextHash);
        configService.saveUserPasswordHash(user.id(), nextHash);
        invalidateUserTokens(user.id());
        UserItem next = gateway.withUserLastLoginAt(user, OffsetDateTime.now());
        gateway.putUser(next);
        gateway.persistUserSnapshot(next);
        String token = issueUserToken(next.id());
        appendOperation("USER_PASSWORD_RESET", "USER", String.valueOf(next.id()), terminal + ":" + account);
        return new AuthSession<>(token, gateway.withGroupName(next));
    }

    /**
     * 管理端重置会员口令（原先内联在仓储 {@code updateUserCredentials} 里的四步）。
     *
     * <p>口令哈希表与令牌表都在本类，所以整块留在鉴权域；会员域经
     * {@link UserGateway#resetUserPassword} 反向调进来。
     */
    void resetUserPassword(Long userId, String newPassword) {
        String nextHash = ADMIN_PASSWORD_ENCODER.encode(newPassword);
        userPasswordHashes.put(userId, nextHash);
        configService.saveUserPasswordHash(userId, nextHash);
        invalidateUserTokens(userId);
    }

    Optional<UserItem> findUserByToken(String token) {
        String cleanToken = cleanBearerToken(token);
        if (StringUtils.hasText(cleanToken) && securityStateStore != null) {
            Optional<Long> redisUserId = securityStateStore.loadUserToken(cleanToken);
            if (redisUserId.isPresent()) {
                return gateway.findUserSnapshot(redisUserId.get()).map(gateway::withGroupName);
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
        return userId == null ? Optional.empty() : gateway.findUserSnapshot(userId).map(gateway::withGroupName);
    }

    void logoutUser(String token) {
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

    // ------------------------------------------------------------------ 管理端登录与会话

    AuthSession<AdminProfile> loginAdmin(LoginRequest request, String clientIp) {
        String account = normalize(request == null ? "" : request.account());
        String password = request == null ? "" : request.password();
        // 批次5 / B2：先判锁定与 IP 频率，再做任何口令比对，避免锁定期继续消耗 BCrypt。
        loginGuard().assertCanAttempt("admin", account, clientIp);
        verifyHumanCaptchaIfRequired("admin", request == null ? "" : request.captchaTicket(), request == null ? "" : request.captchaRandstr(), clientIp);
        if (Objects.equals(adminUsername, account)) {
            if (!ADMIN_PASSWORD_ENCODER.matches(password, adminPasswordBcrypt)) {
                loginGuard().recordFailure("admin", account);
                throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
            }
            if (smsLoginSetting.enabled() && smsLoginSetting.adminLoginEnabled()) {
                String mobile = defaultText(smsLoginSetting.adminMobile(), "").trim();
                if (!isMobile(mobile)) {
                    throw new IllegalStateException("管理员短信验证登录已开启，但未配置管理员手机号");
                }
                verifyLoginSmsCode(verificationKey("ADMIN_LOGIN", "admin", mobile), request == null ? "" : request.code());
            }
            loginGuard().recordSuccess("admin", account);
            AdminProfile profile = new AdminProfile(
                1L,
                adminUsername,
                adminNickname,
                ALL_ADMIN_PERMISSIONS
            );
            return issueAdminSession(profile);
        }

        Optional<AdminStaffItem> matchedStaff = adminStaff.values().stream()
            .filter(item -> Objects.equals(normalize(item.account()), account))
            .findFirst();
        if (matchedStaff.isEmpty()) {
            // 账号不存在时也走一次等价开销的 BCrypt 比对，避免响应时间成为账号枚举侧信道。
            burnEquivalentPasswordWork(password);
            loginGuard().recordFailure("admin", account);
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
        AdminStaffItem staff = matchedStaff.get();
        String hash = adminStaffPasswordHashes.get(staff.id());
        if (!StringUtils.hasText(hash) || !ADMIN_PASSWORD_ENCODER.matches(password, hash)) {
            loginGuard().recordFailure("admin", account);
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
        if (!"ENABLED".equalsIgnoreCase(defaultText(staff.status(), ""))) {
            // 口令校验通过后才提示停用，否则「停用」提示本身就是账号存在性的枚举信号。
            throw new IllegalStateException("员工账号已停用，请联系超级管理员");
        }
        if (smsLoginSetting.enabled() && smsLoginSetting.adminLoginEnabled()) {
            String mobile = defaultText(smsLoginSetting.adminMobile(), "").trim();
            if (!isMobile(mobile)) {
                throw new IllegalStateException("管理员短信验证登录已开启，但未配置管理员手机号");
            }
            verifyLoginSmsCode(verificationKey("ADMIN_LOGIN", "admin", mobile), request == null ? "" : request.code());
        }
        loginGuard().recordSuccess("admin", account);
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

    Optional<AdminProfile> findAdminByToken(String token) {
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

    void logoutAdmin(String token) {
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

    AdminProfile updateSuperAdminCredentials(String token, AdminCredentialRequest request) {
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
        configService.saveSuperAdminCredentials(adminUsername, adminNickname, adminPasswordBcrypt);
        logoutAllAdmins();
        appendOperation("SUPER_ADMIN_CREDENTIAL_UPDATE", "ADMIN", "1", adminUsername);
        return new AdminProfile(1L, adminUsername, adminNickname, ALL_ADMIN_PERMISSIONS);
    }

    // ------------------------------------------------------------------ 员工账号

    List<AdminStaffItem> listAdminStaff() {
        return adminStaff.values().stream()
            .sorted(Comparator.comparing(AdminStaffItem::id))
            .toList();
    }

    AdminStaffItem createAdminStaff(AdminStaffRequest request) {
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

    AdminStaffItem updateAdminStaff(Long id, AdminStaffRequest request) {
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

    void deleteAdminStaff(Long id) {
        AdminStaffItem removed = adminStaff.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("员工账号不存在");
        }
        adminStaffPasswordHashes.remove(id);
        persistAdminStaff();
        appendOperation("ADMIN_STAFF_DELETE", "ADMIN_STAFF", String.valueOf(id), removed.account());
    }

    private AdminStaffItem adminStaffFromPayload(Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AdminStaffItem(
            longValue(payload.get("id"), null),
            normalize(defaultText(payload.get("account"), "")),
            defaultText(payload.get("nickname"), "员工账号"),
            normalizeAdminStaffStatus(payload.get("status")),
            normalizeAdminPermissions(gateway.stringList(payload.get("permissions"))),
            Optional.ofNullable(gateway.parseOffsetDateTime(defaultText(payload.get("createdAt"), ""))).orElse(now),
            Optional.ofNullable(gateway.parseOffsetDateTime(defaultText(payload.get("updatedAt"), ""))).orElse(now)
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
        List<String> permissions = gateway.normalizeTextList(values).stream()
            .filter(allowed::contains)
            .toList();
        return permissions.isEmpty() ? List.of("dashboard:read") : permissions;
    }

    private long maxAdminStaffId() {
        return adminStaff.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(999L);
    }

    /**
     * 与仓储 {@code allocateNextCandidateId} 逐字一致的一份拷贝。
     *
     * <p>{@code adminStaffId} 序列已随员工账号搬进本类，仓储侧那支只服务其它域；
     * 三行的纯函数就地复制，比为它开一个 gateway 方法更清楚（批次7B 的
     * {@code CatalogService} 同样处理）。
     */
    private Long allocateNextCandidateId(AtomicLong sequence, long maxExistingId) {
        long id = Math.max(sequence.get(), maxExistingId + 1);
        sequence.set(id + 1);
        return id;
    }

    // ------------------------------------------------------------------ 令牌与口令

    private String issueUserToken(Long userId) {
        String token = "h5_" + UUID.randomUUID();
        userTokens.put(token, userId);
        userTokenExpiresAt.put(token, OffsetDateTime.now().plus(USER_TOKEN_TTL));
        if (securityStateStore != null) {
            securityStateStore.storeUserToken(token, userId, USER_TOKEN_TTL);
        }
        return token;
    }

    void invalidateUserTokens(Long userId) {
        userTokens.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), userId));
        userTokenExpiresAt.keySet().removeIf(token -> !userTokens.containsKey(token));
        if (securityStateStore != null) {
            securityStateStore.invalidateUserTokens(userId);
        }
    }

    void validateNewPassword(String password, String confirmPassword) {
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

    private boolean isTokenExpired(String token, Map<String, OffsetDateTime> expiresAtByToken) {
        OffsetDateTime expiresAt = expiresAtByToken.get(token);
        return expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now());
    }

    private void verifyUserPassword(UserItem user, LoginRequest request) {
        String password = request == null ? "" : defaultText(request.password(), request.code());
        if (user == null) {
            burnEquivalentPasswordWork(password);
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
        String hash = userPasswordHashes.computeIfAbsent(user.id(), this::persistentUserPasswordHash);
        if (!StringUtils.hasText(hash)) {
            // 用户存在但没有口令哈希：同样要走等价开销的比对，文案与口令错误完全一致。
            burnEquivalentPasswordWork(password);
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
        if (!ADMIN_PASSWORD_ENCODER.matches(password, hash)) {
            throw new IllegalArgumentException(UNIFIED_LOGIN_FAILURE_MESSAGE);
        }
    }

    private String persistentUserPasswordHash(Long userId) {
        if (configPersistenceStore == null || userId == null) {
            return "";
        }
        try {
            return defaultText(configService.userPasswordHash(userId), "");
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FAILED", "USER_PASSWORD", String.valueOf(userId), gateway.persistenceErrorMessage(ex));
            throw new IllegalStateException("密码服务暂不可用，请稍后重试", ex);
        }
    }

    // ------------------------------------------------------------------ 短信验证码

    private boolean isRegistrationSmsCodeRequired() {
        return "MOBILE".equals(gateway.normalizeRegistrationType(configService.systemSetting().registrationType()));
    }

    private boolean isUserSmsLoginRequired(String terminal) {
        if (!smsLoginSetting.enabled()) {
            return false;
        }
        return switch (gateway.normalizeTerminal(terminal)) {
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
        auditService.appendSmsLog(
            "LOGIN",
            mobile,
            purpose + ":" + gateway.normalizeTerminal(terminal),
            "登录验证码短信",
            status,
            error,
            now
        );
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

    /**
     * 滑块票据校验。
     *
     * <p><b>遗留项（批次8D 发现，本次未改）</b>：{@code createSliderToken} 由 H5 与管理端
     * 两个控制器暴露，但全仓没有任何调用点会走到本方法 —— 票据只发不验。
     * 本次是纯结构重构，逐字搬过来，不顺手改行为。
     */
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

    /** 服务商分发留在鉴权域，出网动作反向调回仓储（签名工具与 HttpClient 封装在那边）。 */
    private void sendSmsByProvider(String mobile, String code, String content) {
        switch (normalizeSmsProvider(smsLoginSetting.provider())) {
            case "GENERIC" -> gateway.sendGenericSms(smsLoginSetting.genericConfig(), mobile, code, content);
            case "ALIYUN" -> gateway.sendAliyunSms(smsLoginSetting.aliyunConfig(), mobile, code);
            default -> gateway.sendTencentSms(smsLoginSetting.tencentConfig(), mobile, code);
        }
    }

    // ------------------------------------------------------------------ 人机验证判定

    private void verifyHumanCaptchaIfRequired(String terminal, String ticket, String randstr, String clientIp) {
        String cleanTerminal = gateway.normalizeTerminal(terminal);
        if (!isCaptchaRequired(cleanTerminal)) {
            return;
        }
        String provider = normalizeCaptchaProvider(captchaSetting.provider());
        // Altcha 只有 ticket，没有 randstr；其余服务商两者都必须非空。
        if (!StringUtils.hasText(ticket) || (!"ALTCHA".equals(provider) && !StringUtils.hasText(randstr))) {
            throw new IllegalArgumentException("请先完成人机验证");
        }
        switch (provider) {
            case "GENERIC" -> gateway.verifyGenericCaptcha(captchaSetting.genericConfig(), ticket, randstr, clientIp);
            case "TURNSTILE" -> gateway.verifyTurnstileCaptcha(captchaSetting.turnstileConfig(), ticket, clientIp);
            case "ALTCHA" -> altchaService.verify(
                defaultText(captchaSetting.altchaConfig().get("hmac_key"), ""), ticket);
            default -> gateway.verifyTencentCaptcha(captchaSetting.tencentConfig(), ticket, randstr, clientIp);
        }
    }

    private boolean isCaptchaRequired(String terminal) {
        String cleanTerminal = gateway.normalizeTerminal(terminal);
        if ("admin".equals(cleanTerminal) && gateway.adminCaptchaRequired() && isCaptchaProviderConfigured()) {
            // 批次5 / B2：凭据齐备时，管理端强制校验，忽略后台两个默认关闭的开关。
            return true;
        }
        if (!captchaSetting.enabled()) {
            return false;
        }
        return switch (cleanTerminal) {
            case "admin" -> captchaSetting.adminLoginEnabled();
            case "web" -> captchaSetting.webLoginEnabled();
            default -> captchaSetting.h5LoginEnabled();
        };
    }

    /**
     * 人机验证凭据是否已配置齐备。
     *
     * <p>只有配置齐备才能强制管理端验证码；否则强制只会让管理端<b>永久登不进去</b>
     * （前端拿不到 appId 就无法产出 ticket），这属于把可用性问题当安全修复。
     * 未配置时由登录失败计数 + 阶梯锁定 + 同 IP 频率限制承担防护，并在报告中列为遗留项。
     */
    private boolean isCaptchaProviderConfigured() {
        CaptchaSettingItem setting = captchaSetting;
        if (setting == null) {
            return false;
        }
        return switch (normalizeCaptchaProvider(setting.provider())) {
            case "GENERIC" -> hasConfig(setting.genericConfig(), "url");
            case "TURNSTILE" -> hasConfig(setting.turnstileConfig(), "site_key")
                && hasConfig(setting.turnstileConfig(), "secret_key");
            case "ALTCHA" -> hasConfig(setting.altchaConfig(), "hmac_key");
            default -> hasConfig(setting.tencentConfig(), "captcha_app_id")
                && hasConfig(setting.tencentConfig(), "app_secret_key");
        };
    }

    private boolean hasConfig(Map<String, String> config, String key) {
        return config != null && StringUtils.hasText(defaultText(config.get(key), ""));
    }

    private String normalizeCaptchaProvider(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (List.of("TENCENT", "TURNSTILE", "GENERIC", "ALTCHA").contains(normalized)) {
            return normalized;
        }
        return "TENCENT";
    }

    private String normalizeSmsProvider(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (List.of("TENCENT", "ALIYUN", "GENERIC").contains(normalized)) {
            return normalized;
        }
        return "TENCENT";
    }

    // ------------------------------------------------------------------ DB 镜像与启动预热

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
            gateway.putEncryptedConfig(payload, "genericConfig", smsLoginSetting.genericConfig());
            gateway.putEncryptedConfig(payload, "tencentConfig", smsLoginSetting.tencentConfig());
            gateway.putEncryptedConfig(payload, "aliyunConfig", smsLoginSetting.aliyunConfig());
            configService.saveSmsLoginSettingJson(OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SMS_LOGIN_SETTING", "GLOBAL", ex.getMessage());
            throw new IllegalStateException("SMS setting serialization failed", ex);
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
            gateway.putEncryptedConfig(payload, "tencentConfig", captchaSetting.tencentConfig());
            gateway.putEncryptedConfig(payload, "turnstileConfig", captchaSetting.turnstileConfig());
            gateway.putEncryptedConfig(payload, "genericConfig", captchaSetting.genericConfig());
            gateway.putEncryptedConfig(payload, "altchaConfig", captchaSetting.altchaConfig());
            configService.saveCaptchaSettingJson(OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "CAPTCHA_SETTING", "GLOBAL", ex.getMessage());
            throw new IllegalStateException("captcha setting serialization failed", ex);
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
            configService.saveAdminStaffJson(OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "ADMIN_STAFF", "LIST", ex.getMessage());
            throw new IllegalStateException("admin staff serialization failed", ex);
        }
    }

    void loadSuperAdminCredentials() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            Map<String, String> settings = configService.superAdminCredentials();
            String username = normalize(defaultText(settings.get("username"), ""));
            String passwordHash = defaultText(settings.get("passwordHash"), "");
            String nickname = defaultText(settings.get("nickname"), "").trim();
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
            gateway.recordReadFallback("SUPER_ADMIN", "CREDENTIALS", ex);
        }
    }

    void loadSmsLoginSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configService.smsLoginSettingJson();
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
                gateway.settingConfig(payload, "genericConfig"),
                gateway.settingConfig(payload, "tencentConfig"),
                gateway.settingConfig(payload, "aliyunConfig")
            );
        } catch (RuntimeException | JsonProcessingException ex) {
            gateway.recordReadFallback("SMS_LOGIN_SETTING", "GLOBAL", ex);
        }
    }

    void loadCaptchaSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configService.captchaSettingJson();
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
                gateway.settingConfig(payload, "tencentConfig"),
                gateway.settingConfig(payload, "turnstileConfig"),
                gateway.settingConfig(payload, "genericConfig"),
                gateway.settingConfig(payload, "altchaConfig")
            );
        } catch (RuntimeException | JsonProcessingException ex) {
            gateway.recordReadFallback("CAPTCHA_SETTING", "GLOBAL", ex);
        }
    }

    void loadAdminStaff() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            String raw = configService.adminStaffJson();
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
            gateway.recordReadFallback("ADMIN_STAFF", "LIST", ex);
        }
    }

    // ------------------------------------------------------------------ 小工具（与仓储逐字一致的本地拷贝）
    //
    // 沿用批次7C 的处理：normalize / defaultText / clampInt / intValue / booleanValue /
    // longValue 都是几行的纯函数，就地复制比为它们各开一个 gateway 方法更清楚。

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
}
