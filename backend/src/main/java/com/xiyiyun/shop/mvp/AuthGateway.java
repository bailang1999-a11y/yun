package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.security.LoginAttemptGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批次8D / 任务D1：{@link AuthService} 反向依赖仓储的那一小组能力。
 *
 * <p>和批次6/7B/7C 的 {@code MonitorGateway} / {@link CatalogGateway} / {@link UserGateway}
 * 一样，只声明鉴权域<b>确实用得到</b>的方法，不把仓储整个塞进来：
 * 接口窄，才能看出「登录域还欠仓储什么」，也才能在后续批次里逐项还清。
 *
 * <p>包级（不写 public）：实现类是 {@code InMemoryShopRepository} 的私有内部类，
 * 调用方只有同包的 {@link AuthService}，没有任何跨包需求。
 */
interface AuthGateway {

    // ---- 字段注入的晚绑定依赖（批次5 / B2 的登录加固组件） ----

    /**
     * 当前登录节流器，可能为 null。
     *
     * <p><b>必须每次现取，不能在构造时缓存</b>：{@code loginAttemptGuard} 在仓储上是
     * {@code @Autowired(required = false)} 字段注入（4 个测试类直接 new 仓储构造器，
     * 加构造参数会让原本绿的用例全部编译失败），而且
     * {@code replaceLoginAttemptGuardForTest} 会在运行期整个换掉实现。
     * 缓存一份就等于把 {@code LoginBruteForceProtectionTest} 注入的阶梯参数丢掉。
     * 语义与 {@link UserGateway#fundsLedger()} 同理。
     */
    LoginAttemptGuard loginAttemptGuard();

    /**
     * 管理端是否强制人机验证（{@code xiyiyun.login.admin-captcha-required}，默认 true）。
     *
     * <p>同样是仓储上的 {@code @Value} 字段注入，理由同 {@link #loginAttemptGuard()}，
     * 现取不缓存。
     */
    boolean adminCaptchaRequired();

    // ---- 短信 / 人机验证的 HTTP 传输层（留在仓储） ----
    //
    // 这几个方法是纯出网调用：签名算法（tencentAuthorization / canonicalQuery /
    // hmacSha256 / sha256Hex / percentEncode）、HttpClient 封装（sendHttp）、
    // 报文模板（applySmsTemplate / jsonEscape）都在仓储，且腾讯云签名与阿里云签名
    // 还被支付渠道等非登录代码共用。搬过来会把整套签名工具复制一遍，
    // 所以只把「用哪个服务商、拿哪份配置」的决策留在鉴权域，出网动作反向调回仓储。
    //
    // 配置一律由 AuthService 以 Map 传入：smsLoginSetting / captchaSetting 已经搬走，
    // 仓储侧不能再直接读它们，否则就成了 repo -> authService -> repo 的读回环。

    void sendGenericSms(Map<String, String> config, String mobile, String code, String content);

    void sendTencentSms(Map<String, String> config, String mobile, String code);

    void sendAliyunSms(Map<String, String> config, String mobile, String code);

    void verifyTencentCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp);

    void verifyTurnstileCaptcha(Map<String, String> config, String token, String clientIp);

    void verifyGenericCaptcha(Map<String, String> config, String ticket, String randstr, String clientIp);

    String testTencentCaptchaSetting(Map<String, String> config);

    String testTurnstileCaptchaSetting(Map<String, String> config);

    String testGenericCaptchaSetting(Map<String, String> config);

    // ---- 配置密文编解码与文本归一（留在仓储） ----

    /**
     * 把一份配置 Map 以「敏感项加密 + nonce」的形状写进待落库 payload。
     *
     * <p>加解密走 {@code ConfigService} 的密钥派生，敏感键判定
     * （{@code isSensitiveConfigKey}）由支付渠道、供应商凭据、短信/验证码凭据共用，
     * 不属登录域私有，故留在仓储。
     */
    void putEncryptedConfig(Map<String, Object> payload, String key, Map<String, String> config);

    /** {@link #putEncryptedConfig} 的逆操作：从 payload 读回并解密一份配置。 */
    Map<String, String> settingConfig(Map<String, Object> payload, String key);

    /** 配置键值归一。支付渠道配置也在用同一支实现，留在仓储避免两份口径。 */
    Map<String, String> normalizeSmsConfig(Map<String, String> config);

    /** 终端白名单归一（admin/h5/web/api/all，默认 h5）。支付渠道终端也在用。 */
    String normalizeTerminal(String value);

    /** 注册方式归一（MOBILE/EMAIL/FREE）。站点设置侧也在用。 */
    String normalizeRegistrationType(String value);

    /** 文本列表去空去重。实现已在批次7B 归 {@link CatalogService}，此处经仓储转发。 */
    List<String> normalizeTextList(List<String> values);

    /** 宽松的时间解析：解析失败返回 null 而不抛，与重构前逐字一致。 */
    OffsetDateTime parseOffsetDateTime(String value);

    /** JSON 里可能是 List 也可能是单个字符串的字段，统一读成 List。 */
    List<String> stringList(Object value);

    // ---- 审计与读降级（留在仓储 / AuditService） ----

    /**
     * DB 读失败时的统一降级记录：WARN 日志 + {@code PERSISTENCE_READ_FALLBACK} 审计。
     *
     * <p>审计动作名与日志文案是运维排查的锚点，必须与仓储其余 16 处完全一致，
     * 因此不在鉴权域重写一份。
     */
    void recordReadFallback(String targetType, String targetId, Exception ex);

    /** 异常文案截断（300 字），与 {@link #recordReadFallback} 同源。 */
    String persistenceErrorMessage(Exception ex);

    // ---- 会员域读写（批次7C 已归 {@link UserService}，登录流程经仓储转发） ----
    //
    // 登录 / 注册 / 找回密码要按账号找人、写 lastLoginAt、落库快照。这些都是会员域
    // 的表与规则，不能在鉴权域再建一份内存态，故一律反向调回。

    List<UserItem> allUserSnapshots();

    Optional<UserItem> findUserSnapshot(Long id);

    UserItem requiredUser(Long id);

    UserItem withGroupName(UserItem user);

    UserItem withUserLastLoginAt(UserItem user, OffsetDateTime lastLoginAt);

    UserItem createUserFromAccount(String account, String username);

    /** 写回会员内存表（{@code UserService.usersMap()} 的同一个引用）。 */
    void putUser(UserItem user);

    void persistUserSnapshot(UserItem user);

    /** 按站点设置校验注册账号形态（手机号 / 邮箱 / 免验证）。 */
    void validateRegistration(String account);
}
