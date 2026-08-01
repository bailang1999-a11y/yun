package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.persistence.CatalogPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.FundsLedgerStore;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

/**
 * 批次7C / 任务C1：会员域（会员 / 会员组 / 组规则 / 会员 API 凭据 / 余额展示）的唯一出口。
 *
 * <h2>为什么单独一层</h2>
 * 这四组实体原先和订单、卡密、上游 HTTP、登录令牌一起挤在
 * {@code InMemoryShopRepository} 里。它们的内存 Map、id 序列、DB 镜像、读降级、
 * 组规则匹配、下单权限校验，全都散落在仓储各处。本类把这些收口：仓储不再直接持有
 * {@code users} / {@code userGroups} / {@code groupRules} / {@code memberCredentials}
 * 任何一张表。
 *
 * <h2>资金变更绝不在本类实现</h2>
 * 批次4 把余额加减做成了 {@link FundsLedgerStore} 上的<b>条件 UPDATE + 按受影响行数判定 +
 * {@code user_balance_transactions} 幂等流水</b>，并且刻意让它住在<b>另一个 bean</b> 里
 * —— {@code @Transactional} 靠 Spring 代理生效，同类内部自调用会绕过代理，事务边界就是假的。
 *
 * <p>所以本类对资金只做三件事：校验入参、<b>调用</b>
 * {@link FundsLedgerStore#creditByAdmin} / {@link FundsLedgerStore#debitByAdmin}、
 * 逐出脏缓存后回读。本类里<b>没有一行</b>{@code users.balance} 的加减 SQL，
 * 也没有绕过流水直接改余额的路径；{@link #adjustUserFundsInMemory} 只在纯内存模式
 * （无 DB，单测）下走，与重构前逐字一致。
 *
 * <h2>为什么依赖靠 {@link UserGateway} 反向注入</h2>
 * 会员域还欠仓储三样东西：令牌与口令（登录域，批次7C/C2 归 {@code AuthService}）、
 * 分类树与文本归一（商品域，批次7B 已归 {@link CatalogService}）、
 * 以及资金 store 的<b>当前引用</b>（{@code fundsLedgerStore} 在仓储上是
 * {@code @Autowired(required = false)} 字段注入，且测试会用
 * {@code replaceFundsLedgerStoreForTest} 换掉，所以只能每次经 gateway 取最新值，
 * 不能在构造时拷一份）。
 *
 * <h2>为什么放在 mvp 包</h2>
 * {@link UserItem} / {@link UserGroupItem} / {@link GroupRuleItem} /
 * {@link MemberApiCredentialItem} 等 DTO 全在 mvp，其中若干是 package-private。
 * 沿用批次2/3/6/7B 的判断：接口与新服务优先留在 mvp，不为了教条的包隔离去搬迁几十个 DTO。
 */
public class UserService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final UserGateway gateway;
    private final AuditService auditService;
    private final ConfigService configService;
    private final CatalogPersistenceStore catalogPersistenceStore;
    private final ConfigPersistenceStore configPersistenceStore;
    private final RedisSecurityStateStore securityStateStore;

    UserService(
        UserGateway gateway,
        AuditService auditService,
        ConfigService configService,
        CatalogPersistenceStore catalogPersistenceStore,
        ConfigPersistenceStore configPersistenceStore,
        RedisSecurityStateStore securityStateStore
    ) {
        this.gateway = gateway;
        this.auditService = auditService;
        this.configService = configService;
        this.catalogPersistenceStore = catalogPersistenceStore;
        this.configPersistenceStore = configPersistenceStore;
        this.securityStateStore = securityStateStore;
    }

    // ------------------------------------------------------------------ 内存态访问（供仓储的订单 / 登录域读取）

    /**
     * 会员内存表。
     *
     * <p>仓储侧的订单与登录代码仍需按 id 读写这张表（下单扣款的内存路径、
     * 登录后写 lastLoginAt 等）。与批次7B 的 {@code goodsMap()} 同样的处理：
     * 暴露<b>同一个 Map 引用</b>，不复制快照，语义与重构前逐字一致。
     */
    Map<Long, UserItem> usersMap() {
        return users;
    }

    Map<String, MemberApiCredentialItem> memberCredentialsMap() {
        return memberCredentials;
    }

    // ------------------------------------------------------------------ 转发到 AuditService / gateway（与仓储同名同语义）

    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        auditService.appendOperation(action, resourceType, resourceId, remark);
    }

    /**
     * 会员 API 签名用的 HMAC-SHA256。
     *
     * <p>仓储那边同名的 {@code hmacSha256} 还有一个 {@code byte[]} 重载给腾讯云 / 阿里云
     * 短信签名用，留在仓储；这里只需要 {@code (String, String)} 这一支，就地复制一份，
     * 实现与仓储原方法逐字相同。
     */
    private String memberApiSignature(String secret, String payload) {
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

    // ------------------------------------------------------------------ 以下为从 InMemoryShopRepository 整块搬入的会员域实现

    private static final Duration MEMBER_API_NONCE_TTL = Duration.ofMinutes(5);

    private static final String DEFAULT_PRICE_LIMIT_NOTICE = "当前会员组暂未开放限价商品购买权限，请联系平台客服处理。";

    private final Map<Long, UserGroupItem> userGroups = new ConcurrentHashMap<>();

    private final Map<Long, UserItem> users = new ConcurrentHashMap<>();

    private final Map<String, MemberApiCredentialItem> memberCredentials = new ConcurrentHashMap<>();

    private final Map<String, OffsetDateTime> memberNonceExpiresAt = new ConcurrentHashMap<>();

    private final Map<String, GroupRuleItem> groupRules = new ConcurrentHashMap<>();

    private final AtomicLong userId = new AtomicLong(90003);

    /** 人工资金调整的幂等业务号序列（批次4）。 */
    private final AtomicLong fundAdjustSequence = new AtomicLong(1);

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

    /**
     * 批次8C：会员分页。降级语义与 {@link #listUsers} 一致 ——
     * 持久层缺失（单元测试无 DB）或读失败时回落到内存 Map，
     * 失败时由 {@code catalogPersistenceStore} 调用处记 {@code PERSISTENCE_READ_FALLBACK}。
     *
     * <p>{@code withGroupName} 只作用在<b>本页</b>的行上：会员组是有界的配置数据，
     * 逐行补组名的代价随页大小而不是会员总量增长，这也是分页真正省下的那部分开销。
     *
     * <p>排序不在这里做：DB 路径由 SQL 的 {@code ORDER BY id} 决定全局顺序，
     * 切页后再排只会打乱页间关系；内存路径在 {@code PageSlice.of} 之前先排完整列表，
     * 与 SQL 的 id 升序对齐。
     */
    public PageSlice<UserItem> pageUsers(int limit, long offset) {
        if (catalogPersistenceStore != null) {
            try {
                PageSlice<UserItem> slice = catalogPersistenceStore.pageUsers(limit, offset);
                return new PageSlice<>(
                    slice.items().stream().map(this::withGroupName).toList(),
                    slice.total()
                );
            } catch (RuntimeException ex) {
                appendOperation("PERSISTENCE_READ_FALLBACK", "USER", "LIST", persistenceErrorMessage(ex));
            }
        }
        return PageSlice.of(
            users.values().stream()
                .map(this::withGroupName)
                .sorted(Comparator.comparing(UserItem::id))
                .toList(),
            limit,
            offset
        );
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
            user.verificationStatus(),
            user.username()
        );
        users.put(userId, next);
        persistUserSnapshot(next);
        return next;
    }

    public synchronized UserItem adminCreateUser(AdminCreateUserRequest request) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("请输入用户账号");
        }
        boolean isEmail = account.contains("@");
        if (isEmail) {
            if (!account.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new IllegalArgumentException("请输入正确的邮箱格式");
            }
        } else {
            if (!account.matches("^1[3-9]\\d{9}$")) {
                throw new IllegalArgumentException("请输入正确的手机号");
            }
        }
        boolean exists = allUserSnapshots().stream()
            .anyMatch(u -> Objects.equals(normalize(u.mobile()), account) || Objects.equals(normalize(u.email()), account));
        if (exists) {
            throw new IllegalArgumentException("该账号已被注册");
        }
        String password = defaultText(request == null ? "" : request.password(), "");
        String confirmPassword = defaultText(request == null ? "" : request.confirmPassword(), "");
        if (StringUtils.hasText(password)) {
            gateway.validateNewPassword(password, confirmPassword);
        }
        UserItem user = createUserFromAccount(account, "");
        String nickname = defaultText(request == null ? "" : request.nickname(), "").trim();
        if (StringUtils.hasText(nickname) && !nickname.equals(user.nickname())) {
            user = new UserItem(
                user.id(), user.avatar(), user.mobile(), user.email(), nickname,
                user.groupId(), user.groupName(), user.balance(), user.deposit(),
                user.status(), user.createdAt(), user.lastLoginAt(),
                user.realNameType(), user.realName(), user.subjectName(),
                user.certificateNo(), user.verificationStatus(), user.username()
            );
            users.put(user.id(), user);
            persistUserSnapshot(user);
        }
        if (StringUtils.hasText(password)) {
            gateway.resetUserPassword(user.id(), password);
        }
        appendOperation("ADMIN_CREATE_USER", "USER", String.valueOf(user.id()), account);
        return user;
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
            gateway.validateNewPassword(newPassword, confirmPassword);
            gateway.resetUserPassword(userId, newPassword);
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
            user.verificationStatus(),
            user.username()
        );
        users.put(userId, next);
        persistUserSnapshot(next);
        appendOperation("USER_CREDENTIAL_UPDATE", "USER", String.valueOf(userId), passwordChanged ? "管理员修改账号并重置密码" : "管理员修改账号");
        return withGroupName(next);
    }

    /**
     * 人工调整用户资金（缺陷 A4 的另一半）。
     *
     * <p>批次4 去掉了方法级 {@code synchronized}。原来它锁 {@code this}，而 {@code payOrder}
     * 扣款锁 {@code orderLock}——<b>两把互不排斥的监视器</b>，于是"读余额 → 算新值 → 写回"
     * 与并发扣款之间存在真实的交叉窗口，产生丢失更新。
     *
     * <p>现在持久化模式下走 {@link FundsLedgerStore}：
     * {@code UPDATE users SET balance = balance ± ?} 条件更新 + 同事务写流水，
     * 原子性由数据库行锁保证，JVM 锁反而只会在持锁期间做 DB IO、放大争用，因此移除。
     */
    public UserItem adjustUserFunds(Long userId, UserFundAdjustRequest request) {
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
        if (gateway.fundsLedgerEnabled()) {
            return adjustUserFundsAtomically(userId, request, amount, accountType, direction);
        }
        return adjustUserFundsInMemory(userId, request, amount, accountType, direction);
    }

    /** DB 条件更新 + 记账；每次调整用独立 biz_no，保证 15 次并发调整留 15 条流水。 */
    private UserItem adjustUserFundsAtomically(
        Long userId, UserFundAdjustRequest request, BigDecimal amount, String accountType, String direction
    ) {
        if (findUserSnapshot(userId).isEmpty()) {
            throw new IllegalArgumentException("user not found");
        }
        boolean deposit = "deposit".equals(accountType);
        String bizNo = nextFundAdjustNo(userId);
        String remark = defaultText(request.remark(), "管理员调整" + (deposit ? "保证金" : "余额"));
        if ("increase".equals(direction)) {
            gateway.fundsLedger().creditByAdmin(userId, amount, bizNo, remark, deposit);
        } else {
            gateway.fundsLedger().debitByAdmin(userId, amount, bizNo, remark, deposit);
        }
        // 逐出脏缓存，下次读走 DB
        users.remove(userId);
        appendOperation(
            "USER_FUND_ADJUST",
            "USER",
            String.valueOf(userId),
            accountType + ":" + direction + ":" + amount + ":" + defaultText(request.remark(), "")
        );
        return findUserSnapshot(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    private String nextFundAdjustNo(Long userId) {
        return "ADJ" + userId + "-" + System.nanoTime() + "-" + fundAdjustSequence.getAndIncrement();
    }

    private UserItem adjustUserFundsInMemory(
        Long userId, UserFundAdjustRequest request, BigDecimal amount, String accountType, String direction
    ) {
        UserItem user = findUserSnapshot(userId).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
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
            user.verificationStatus(),
            user.username()
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

    private List<OpenApiLogItem> allOpenApiLogSnapshots() {
        return auditService.allOpenApiLogSnapshots();
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
        String callbackUrl = request == null || request.callbackUrl() == null
            ? current.callbackUrl()
            : request.callbackUrl().trim();
        if (callbackUrl.length() > 500) {
            throw new IllegalArgumentException("callback url length cannot exceed 500");
        }
        if (StringUtils.hasText(callbackUrl)) {
            MemberCallbackUrlPolicy.validateConfiguration(callbackUrl);
        }
        String status = request == null || request.enabled() == null
            ? current.status()
            : Boolean.TRUE.equals(request.enabled()) ? "ENABLED" : "DISABLED";
        List<String> ipWhitelist = request == null || request.ipWhitelist() == null
            ? current.ipWhitelist()
            : gateway.normalizeTextList(request.ipWhitelist());
        int dailyLimit = request == null || request.dailyLimit() == null
            ? current.dailyLimit()
            : Math.max(1, request.dailyLimit());
        MemberApiCredentialItem next = new MemberApiCredentialItem(
            current.id(),
            userId,
            appKey,
            secret,
            callbackUrl,
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
        return authenticateMemberApi(appKey, timestamp, nonce, signature, path, clientIp, "");
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
        String payload = timestamp + "\n" + nonce + "\n" + path
            + (StringUtils.hasText(contentHash) ? "\n" + contentHash.trim().toLowerCase(Locale.ROOT) : "");
        if (!constantTimeEquals(memberApiSignature(credential.appSecret(), payload), signature)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "invalid signature");
            throw new IllegalArgumentException("invalid signature");
        }
        UserItem user = findUserSnapshot(credential.userId()).orElse(null);
        if (user == null) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "user not found");
            throw new IllegalArgumentException("user not found");
        }
        long successfulRequestsToday = allOpenApiLogSnapshots().stream()
            .filter(log -> Objects.equals(log.userId(), credential.userId()))
            .filter(log -> Objects.equals(log.appKey(), appKey))
            .filter(log -> "SUCCESS".equals(log.status()))
            .filter(log -> log.createdAt() != null && log.createdAt().toLocalDate().equals(OffsetDateTime.now().toLocalDate()))
            .count();
        if (successfulRequestsToday >= credential.dailyLimit()) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "daily limit exceeded");
            throw new IllegalStateException("daily limit exceeded");
        }
        String nonceKey = appKey + ":" + nonce;
        if (isMemberNonceReplay(nonceKey, OffsetDateTime.now())) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "nonce replay");
            throw new IllegalArgumentException("nonce replay");
        }
        memberCredentials.put(appKey, new MemberApiCredentialItem(
            credential.id(),
            credential.userId(),
            credential.appKey(),
            credential.appSecret(),
            credential.callbackUrl(),
            credential.status(),
            credential.ipWhitelist(),
            credential.dailyLimit(),
            credential.createdAt(),
            OffsetDateTime.now()
        ));
        appendOpenApiLog(user.id(), appKey, path, "SUCCESS", "ok");
        return withGroupName(user);
    }

    OutboundApiPrincipal prepareOutboundCredential(String appKey, String path, String clientIp) {
        String normalizedAppKey = defaultText(appKey, "").trim();
        MemberApiCredentialItem credential = memberCredentials.get(normalizedAppKey);
        if (credential == null) {
            credential = persistentMemberCredentialByAppKey(normalizedAppKey).orElse(null);
            if (credential != null) {
                memberCredentials.put(credential.appKey(), credential);
            }
        }
        if (credential == null || !"ENABLED".equals(credential.status())) {
            appendOpenApiLog(null, normalizedAppKey, path, "FAILED", "invalid app key");
            throw new IllegalArgumentException("invalid app key");
        }
        if (!isMemberApiIpAllowed(credential, clientIp)) {
            appendOpenApiLog(credential.userId(), normalizedAppKey, path, "FAILED", "ip not allowed");
            throw new IllegalArgumentException("ip not allowed");
        }
        UserItem user = findUserSnapshot(credential.userId()).orElse(null);
        String userStatus = user == null ? "" : defaultText(user.status(), "").toUpperCase(Locale.ROOT);
        if (user == null || !("NORMAL".equals(userStatus) || "ACTIVE".equals(userStatus))) {
            appendOpenApiLog(credential.userId(), normalizedAppKey, path, "FAILED", "user unavailable");
            throw new IllegalArgumentException("user unavailable");
        }
        if (successfulRequestsToday(credential) >= credential.dailyLimit()) {
            appendOpenApiLog(credential.userId(), normalizedAppKey, path, "FAILED", "daily limit exceeded");
            throw new IllegalStateException("daily limit exceeded");
        }
        return new OutboundApiPrincipal(withGroupName(user), credential);
    }

    void completeOutboundCredential(OutboundApiPrincipal principal, String path) {
        if (principal == null || principal.credential() == null) {
            return;
        }
        MemberApiCredentialItem credential = principal.credential();
        MemberApiCredentialItem used = new MemberApiCredentialItem(
            credential.id(), credential.userId(), credential.appKey(), credential.appSecret(), credential.callbackUrl(),
            credential.status(), credential.ipWhitelist(), credential.dailyLimit(), credential.createdAt(), OffsetDateTime.now()
        );
        memberCredentials.put(used.appKey(), used);
        persistMemberCredential(used);
        appendOpenApiLog(used.userId(), used.appKey(), path, "SUCCESS", "ok");
    }

    void rejectOutboundCredential(OutboundApiPrincipal principal, String appKey, String path, String message) {
        Long userId = principal == null || principal.credential() == null ? null : principal.credential().userId();
        String resolvedAppKey = principal == null || principal.credential() == null
            ? defaultText(appKey, "")
            : principal.credential().appKey();
        appendOpenApiLog(userId, resolvedAppKey, path, "FAILED", defaultText(message, "request rejected"));
    }

    private long successfulRequestsToday(MemberApiCredentialItem credential) {
        return allOpenApiLogSnapshots().stream()
            .filter(log -> Objects.equals(log.userId(), credential.userId()))
            .filter(log -> Objects.equals(log.appKey(), credential.appKey()))
            .filter(log -> "SUCCESS".equals(log.status()))
            .filter(log -> log.createdAt() != null && log.createdAt().toLocalDate().equals(OffsetDateTime.now().toLocalDate()))
            .count();
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

    UserItem requiredUser(Long id) {
        UserItem item = findUserSnapshot(id).orElse(null);
        if (item == null) {
            throw new IllegalArgumentException("user not found");
        }
        return item;
    }

    void persistUserSnapshot(UserItem user) {
        if (catalogPersistenceStore == null || user == null) {
            return;
        }
        try {
            catalogPersistenceStore.saveUserSnapshot(user);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "USER", String.valueOf(user.id()), persistenceErrorMessage(ex));
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
            throw ex;
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
            throw ex;
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
            payload.put("callbackUrl", item.callbackUrl());
            if (configPersistenceStore != null && StringUtils.hasText(item.appSecret())) {
                Map<String, String> encryptedSecret = configService.encryptSecretForSetting(item.appSecret());
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
            configService.saveMemberCredentialJson(item.userId(), OBJECT_MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "MEMBER_API", String.valueOf(item.userId()), ex.getMessage());
            throw new IllegalStateException("member credential serialization failed", ex);
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

    private Optional<MemberApiCredentialItem> persistentMemberCredential(Long userId) {
        if (configPersistenceStore == null || userId == null) {
            return Optional.empty();
        }
        try {
            String raw = configService.memberCredentialJson(userId);
            if (!StringUtils.hasText(raw)) {
                return Optional.empty();
            }
            return Optional.of(memberCredentialFromJson(raw, userId));
        } catch (Exception ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "MEMBER_API", String.valueOf(userId), ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<MemberApiCredentialItem> persistentMemberCredentialByAppKey(String appKey) {
        if (configPersistenceStore == null || !StringUtils.hasText(appKey)) {
            return Optional.empty();
        }
        try {
            String raw = configService.memberCredentialJsonByAppKey(appKey);
            return StringUtils.hasText(raw) ? Optional.of(memberCredentialFromJson(raw, null)) : Optional.empty();
        } catch (Exception ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "MEMBER_API", appKey, ex.getMessage());
            return Optional.empty();
        }
    }

    private MemberApiCredentialItem memberCredentialFromJson(String raw, Long fallbackUserId) throws JsonProcessingException {
        Map<String, Object> payload = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
        Long userId = longValue(payload.get("userId"), fallbackUserId);
        OffsetDateTime createdAt = parseOffsetDateTime(defaultText(payload.get("createdAt"), ""));
        OffsetDateTime lastUsedAt = parseOffsetDateTime(defaultText(payload.get("lastUsedAt"), ""));
        return new MemberApiCredentialItem(
            longValue(payload.get("id"), memberCredentials.values().stream().map(MemberApiCredentialItem::id).max(Long::compareTo).orElse(0L) + 1),
            userId,
            defaultText(payload.get("appKey"), memberAppKey(userId)),
            memberCredentialSecret(payload),
            defaultText(payload.get("callbackUrl"), ""),
            defaultText(payload.get("status"), "DISABLED"),
            stringList(payload.get("ipWhitelist")),
            intValue(payload.get("dailyLimit"), 1000),
            createdAt == null ? OffsetDateTime.now() : createdAt,
            lastUsedAt
        );
    }

    private String memberCredentialSecret(Map<String, Object> payload) {
        String ciphertext = defaultText(payload.get("appSecretCiphertext"), "");
        String nonce = defaultText(payload.get("appSecretNonce"), "");
        if (configPersistenceStore != null && StringUtils.hasText(ciphertext) && StringUtils.hasText(nonce)) {
            return configService.decryptSecretFromSetting(ciphertext, nonce);
        }
        return defaultText(payload.get("appSecret"), memberAppSecret());
    }

    private long maxUserGroupId() {
        long max = userGroups.keySet().stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
        Optional<List<UserGroupItem>> persistent = persistentUserGroups();
        if (persistent.isPresent()) {
            max = Math.max(max, persistent.get().stream().map(UserGroupItem::id).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
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

    Optional<UserGroupItem> findUserGroupSnapshot(Long id) {
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

    List<UserItem> allUserSnapshots() {
        Map<Long, UserItem> snapshots = new java.util.LinkedHashMap<>();
        persistentUsers().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        users.values().forEach(item -> snapshots.put(item.id(), item));
        return snapshots.values().stream()
            .sorted(Comparator.comparing(UserItem::id))
            .toList();
    }

    Optional<UserItem> findUserSnapshot(Long id) {
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

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            defaultText(actual, "").toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void appendOpenApiLog(Long userId, String appKey, String path, String status, String message) {
        // defaultText 归一化留在调用侧，保证与批次6 之前逐字节一致（空白串也归一成 ""）。
        auditService.appendOpenApiLog(userId, defaultText(appKey, ""), defaultText(path, ""), status, message);
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

    List<GroupRuleItem> rulesForGroup(Long groupId) {
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
                    CategoryItem category = rule.targetId() == null ? null : gateway.findCategorySnapshot(rule.targetId()).orElse(null);
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
            CategoryItem category = targetId == null ? null : gateway.findCategorySnapshot(targetId).orElse(null);
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

    boolean allowedByGroupRules(GoodsItem item, List<GroupRuleItem> rules) {
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

    void validateGoodsGroupAccess(UserItem user, GoodsItem item) {
        Long groupId = user.groupId() == null ? 1L : user.groupId();
        if (!allowedByGroupRules(item, rulesForGroup(groupId))) {
            throw new IllegalStateException("当前会员组不可购买该商品。");
        }
    }

    private boolean ruleMatches(GroupRuleItem rule, GoodsItem item) {
        if ("CATEGORY".equals(rule.ruleType())) {
            return rule.targetId() != null && gateway.categoryTreeIds(rule.targetId()).contains(item.categoryId());
        }
        if ("PLATFORM".equals(rule.ruleType())) {
            String targetCode = normalize(rule.targetCode());
            return StringUtils.hasText(targetCode) && item.availablePlatforms().stream()
                .map(this::normalize)
                .anyMatch(targetCode::equals);
        }
        return false;
    }

    void validateOrderPermission(UserItem user) {
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

    void validatePriceLimitPermission(UserItem user, GoodsItem item) {
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

    UserItem withGroupName(UserItem user) {
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
            user.verificationStatus(),
            user.username()
        );
    }

    UserItem withUserBalance(UserItem user, BigDecimal balance) {
        return new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            balance,
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
    }

    UserItem withUserLastLoginAt(UserItem user, OffsetDateTime lastLoginAt) {
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
            user.verificationStatus(),
            user.username()
        );
    }

    UserItem createUserFromAccount(String account, String username) {
        validateRegistration(account);
        String cleanUsername = (username == null || username.isBlank()) ? null : username.trim();
        if (cleanUsername != null && !cleanUsername.matches("^[a-z0-9_-]{1,12}$")) {
            throw new IllegalArgumentException("用户名只能包含小写字母、数字、下划线和连字符，且不超过 12 个字符");
        }
        Long id = allocateIncrementingId(userId, maxUserId());
        boolean email = account.contains("@");
        Long groupId = validDefaultUserGroupId(configService.systemSetting().defaultUserGroupId());
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
            "UNVERIFIED",
            cleanUsername
        );
        // 顺序刻意是「先落库、后写内存」。
        //
        // 反过来写会制造出「内存有、库里没有」的会员：persistUserSnapshot 失败时异常向上抛，
        // 注册请求确实会报错，但内存 users 里已经留下了这一行。该会员随后能正常下单、
        // 能走支付宝支付，直到外部支付回调要记资金流水时，
        // FundsLedgerStore.lockUser 的 SELECT ... FOR UPDATE 在库里锁不到行，
        // 抛 user not found —— 钱已经收到了，账记不上。生产上已出现过这种会员。
        //
        // 纯内存模式下 persistUserSnapshot 直接 return，交换顺序无影响。
        persistUserSnapshot(user);
        users.put(id, user);
        return user;
    }

    String groupName(Long groupId) {
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

    Long validDefaultUserGroupId(Long groupId) {
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

    /** users.mobile 列宽（varchar(32)）。不含 @ 的账号写这一列。 */
    private static final int MOBILE_COLUMN_LIMIT = 32;
    /** users.email 列宽（varchar(128)）。含 @ 的账号写这一列。 */
    private static final int EMAIL_COLUMN_LIMIT = 128;

    void validateRegistration(String account) {
        if (!configService.systemSetting().registrationEnabled()) {
            throw new IllegalStateException("当前系统暂未开放新用户注册");
        }

        String registrationType = gateway.normalizeRegistrationType(configService.systemSetting().registrationType());
        if ("MOBILE".equals(registrationType) && !account.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("当前仅支持手机号注册");
        }
        if ("EMAIL".equals(registrationType) && !account.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new IllegalArgumentException("当前仅支持邮箱注册");
        }
        requireAccountFitsColumn(account);
    }

    /**
     * 账号长度必须能装进目标列，否则落库会被 MySQL 严格模式以 {@code Data too long} 拒绝。
     *
     * <p>为什么必须显式校验：MOBILE 模式有 11 位手机号正则、EMAIL 模式有邮箱正则，
     * 两者天然限制了长度；但 <b>FREE 模式不校验任何格式</b>，账号可以是任意长度的用户名，
     * 而不含 {@code @} 的账号会被写进 {@code users.mobile}（varchar(32)）。
     *
     * <p>不校验的后果不是"注册失败"这么简单：超长账号会让
     * {@link #persistUserSnapshot} 抛异常，而该会员此时可能已经进了内存表，
     * 于是形成「内存有、库里没有」的会员，最终在外部支付回调记账时炸成
     * {@code user not found}。这里提前拦住，把问题限制在注册这一步。
     */
    private void requireAccountFitsColumn(String account) {
        boolean email = account.contains("@");
        int limit = email ? EMAIL_COLUMN_LIMIT : MOBILE_COLUMN_LIMIT;
        if (account.length() > limit) {
            throw new IllegalArgumentException(
                (email ? "邮箱" : "账号") + "长度不能超过 " + limit + " 个字符"
            );
        }
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
        List<String> whitelist = gateway.normalizeTextList(credential.ipWhitelist());
        if (whitelist.isEmpty()) {
            return true;
        }
        String normalizedIp = defaultText(clientIp, "").trim();
        return whitelist.stream().anyMatch(item -> Objects.equals(item, normalizedIp));
    }

    void seedUserGroups() {
        userGroups.put(1L, new UserGroupItem(1L, "默认会员", "注册后自动归入的基础用户组", true, 0, "ENABLED", true, false, true, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));
        userGroups.put(2L, new UserGroupItem(2L, "渠道 VIP", "仅开放私域，屏蔽人工代充类目", false, 0, "ENABLED", true, true, true, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));
        userGroups.put(3L, new UserGroupItem(3L, "受限会员", "风控观察组，限制游戏直充和淘宝平台购买", false, 0, "ENABLED", false, false, false, DEFAULT_PRICE_LIMIT_NOTICE, List.of()));

        groupRules.put("2:CATEGORY:333", new GroupRuleItem(2L, "CATEGORY", 333L, null, "人工处理", "DENY"));
        groupRules.put("2:PLATFORM:private", new GroupRuleItem(2L, "PLATFORM", null, "private", "私域", "ALLOW"));
        groupRules.put("3:CATEGORY:2", new GroupRuleItem(3L, "CATEGORY", 2L, null, "游戏直充", "DENY"));
        groupRules.put("3:PLATFORM:taobao", new GroupRuleItem(3L, "PLATFORM", null, "taobao", "淘宝", "DENY"));
    }

    void seedUsers() {
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
            "VERIFIED",
            null
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
            "VERIFIED",
            null
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
            "UNVERIFIED",
            null
        ));
    }

    void seedMemberCredentials() {
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

    // ------------------------------------------------------------------ 与仓储同名同实现的小工具
    //
    // 沿用批次6 ConfigService / 批次7B CatalogService 的做法：几行的纯函数就地复制一份
    // （仓储侧另有调用点仍需它们），不为了消重再拉一个静态工具类出来。

    private String persistenceErrorMessage(RuntimeException ex) {
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

    private String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String defaultText(Object value, String fallback) {
        return value == null ? fallback : defaultText(String.valueOf(value), fallback);
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
}
