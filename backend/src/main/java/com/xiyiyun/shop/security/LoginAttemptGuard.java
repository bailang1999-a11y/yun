package com.xiyiyun.shop.security;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 登录暴力破解防护（批次5 / B2）。
 *
 * <h2>为什么需要</h2>
 * 修复前登录路径<b>完全没有失败次数统计</b>：攻击者可以对同一账号无限次试密码，
 * 唯一成本是一次 BCrypt 的服务端开销。人机验证也不是强制的（{@code captchaSetting.enabled()}
 * 默认可关），等于把爆破门槛降到 0。
 *
 * <h2>两个维度</h2>
 * <ol>
 *   <li><b>账号维度阶梯锁定</b>：{@code terminal + 账号} 在滑动窗口内累计失败达到阈值即锁定。
 *       锁定时长按"超出阈值的轮次"翻倍递增（15min → 30min → 60min，上限 maxLock），
 *       让持续爆破的成本指数上升，而一次手滑只付 15 分钟代价。</li>
 *   <li><b>IP 维度频率限制</b>：固定窗口内同 IP 的<b>全部</b>登录尝试（成功也算）超过上限即拒。
 *       这一层拦的是"每个账号只试 3 次、横向刷一万个账号"这种绕过账号锁定的撞库打法。</li>
 * </ol>
 *
 * <h2>存储</h2>
 * 状态优先落 {@link RedisSecurityStateStore}（多实例共享）。Redis 不可用时退化到本进程内存计数，
 * 保证单实例下防护仍然生效，而不是直接放行（fail-closed 而非 fail-open）。
 */
@Component
public class LoginAttemptGuard {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);
    private static final int LOCAL_CAPACITY = 50_000;

    private final RedisSecurityStateStore securityStateStore;
    private final int maxFailures;
    private final Duration failureWindow;
    private final Duration lockDuration;
    private final Duration maxLockDuration;
    private final Duration ipWindow;
    private final int ipMaxAttempts;

    private final Map<String, AtomicLong> localFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> localLockUntil = new ConcurrentHashMap<>();
    private final Map<String, long[]> localIpAttempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptGuard(
        ObjectProvider<RedisSecurityStateStore> securityStateStoreProvider,
        @Value("${xiyiyun.login.max-failures:5}") int maxFailures,
        @Value("${xiyiyun.login.failure-window-seconds:900}") long failureWindowSeconds,
        @Value("${xiyiyun.login.lock-seconds:900}") long lockSeconds,
        @Value("${xiyiyun.login.max-lock-seconds:3600}") long maxLockSeconds,
        @Value("${xiyiyun.login.ip-window-seconds:300}") long ipWindowSeconds,
        @Value("${xiyiyun.login.ip-max-attempts:30}") int ipMaxAttempts
    ) {
        this(
            securityStateStoreProvider == null ? null : securityStateStoreProvider.getIfAvailable(),
            maxFailures,
            failureWindowSeconds,
            lockSeconds,
            maxLockSeconds,
            ipWindowSeconds,
            ipMaxAttempts
        );
    }

    public LoginAttemptGuard(
        RedisSecurityStateStore securityStateStore,
        int maxFailures,
        long failureWindowSeconds,
        long lockSeconds,
        long maxLockSeconds,
        long ipWindowSeconds,
        int ipMaxAttempts
    ) {
        this.securityStateStore = securityStateStore;
        this.maxFailures = maxFailures <= 0 ? 5 : maxFailures;
        this.failureWindow = Duration.ofSeconds(failureWindowSeconds <= 0 ? 900L : failureWindowSeconds);
        this.lockDuration = Duration.ofSeconds(lockSeconds <= 0 ? 900L : lockSeconds);
        this.maxLockDuration = Duration.ofSeconds(maxLockSeconds <= 0 ? 3600L : maxLockSeconds);
        this.ipWindow = Duration.ofSeconds(ipWindowSeconds <= 0 ? 300L : ipWindowSeconds);
        this.ipMaxAttempts = ipMaxAttempts <= 0 ? 30 : ipMaxAttempts;
    }

    /** 默认参数：5 次失败锁 15 分钟；同 IP 5 分钟内最多 30 次尝试。 */
    public static LoginAttemptGuard withDefaults(RedisSecurityStateStore securityStateStore) {
        return new LoginAttemptGuard(securityStateStore, 5, 900L, 900L, 3600L, 300L, 30);
    }

    public int maxFailures() {
        return maxFailures;
    }

    /**
     * 登录动作前置检查：账号被锁或 IP 超频则抛异常。
     *
     * <p>必须在<b>任何</b>密码比对之前调用，否则锁定期内仍会付出 BCrypt 开销，
     * 且"密码对不对"会通过响应时间泄露出去。
     */
    public void assertCanAttempt(String terminal, String account, String clientIp) {
        String subject = subjectKey(terminal, account);
        Duration remaining = lockRemaining(subject);
        if (!remaining.isZero() && !remaining.isNegative()) {
            throw new IllegalStateException("账号已被临时锁定，请在 " + formatMinutes(remaining) + " 后重试");
        }
        if (isIpRateLimited(clientIp)) {
            throw new IllegalStateException("当前网络登录尝试过于频繁，请稍后再试");
        }
    }

    /**
     * 记录一次失败，达到阈值则锁定。
     *
     * @return 本次记录后是否已进入锁定状态
     */
    public boolean recordFailure(String terminal, String account) {
        String subject = subjectKey(terminal, account);
        long failures = incrementFailure(subject);
        if (failures < maxFailures) {
            return false;
        }
        // 超出阈值的轮次：第 maxFailures 次 → 1 倍，每再多 maxFailures 次翻倍
        long overshoot = (failures - maxFailures) / Math.max(1, maxFailures);
        long multiplier = 1L << Math.min(overshoot, 8L);
        Duration lock = lockDuration.multipliedBy(multiplier);
        if (lock.compareTo(maxLockDuration) > 0) {
            lock = maxLockDuration;
        }
        applyLock(subject, lock);
        log.warn("Login locked for subject={} after {} failures, lock={}s", subject, failures, lock.toSeconds());
        return true;
    }

    /** 登录成功后清空该主体的失败计数与锁定。 */
    public void recordSuccess(String terminal, String account) {
        String subject = subjectKey(terminal, account);
        if (securityStateStore != null) {
            securityStateStore.clearLoginFailures(subject);
        }
        localFailures.remove(subject);
        localLockUntil.remove(subject);
    }

    public boolean isLocked(String terminal, String account) {
        Duration remaining = lockRemaining(subjectKey(terminal, account));
        return !remaining.isZero() && !remaining.isNegative();
    }

    // ================================================================= 内部

    private String subjectKey(String terminal, String account) {
        String cleanTerminal = StringUtils.hasText(terminal) ? terminal.trim().toLowerCase() : "unknown";
        String cleanAccount = StringUtils.hasText(account) ? account.trim().toLowerCase() : "";
        return cleanTerminal + ":" + cleanAccount;
    }

    private Duration lockRemaining(String subject) {
        if (securityStateStore != null) {
            Optional<Duration> remaining = securityStateStore.loginLockRemaining(subject);
            if (remaining.isPresent()) {
                return remaining.get();
            }
        }
        Long until = localLockUntil.get(subject);
        if (until == null) {
            return Duration.ZERO;
        }
        long millis = until - System.currentTimeMillis();
        if (millis <= 0) {
            localLockUntil.remove(subject);
            return Duration.ZERO;
        }
        return Duration.ofMillis(millis);
    }

    private long incrementFailure(String subject) {
        if (securityStateStore != null) {
            Optional<Long> count = securityStateStore.incrementLoginFailure(subject, failureWindow);
            if (count.isPresent()) {
                return count.get();
            }
        }
        pruneLocal();
        return localFailures.computeIfAbsent(subject, key -> new AtomicLong()).incrementAndGet();
    }

    private void applyLock(String subject, Duration lock) {
        boolean stored = securityStateStore != null && securityStateStore.lockLogin(subject, lock);
        if (!stored) {
            localLockUntil.put(subject, System.currentTimeMillis() + lock.toMillis());
        }
    }

    private boolean isIpRateLimited(String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return false;
        }
        String ip = clientIp.trim();
        if (securityStateStore != null) {
            Optional<Long> count = securityStateStore.incrementLoginIpAttempt(ip, ipWindow);
            if (count.isPresent()) {
                return count.get() > ipMaxAttempts;
            }
        }
        return localIpAttemptExceeded(ip);
    }

    private boolean localIpAttemptExceeded(String ip) {
        long now = System.currentTimeMillis();
        long[] state = localIpAttempts.compute(ip, (key, current) -> {
            if (current == null || current[1] <= now) {
                return new long[] {1L, now + ipWindow.toMillis()};
            }
            current[0] = current[0] + 1;
            return current;
        });
        localIpAttempts.entrySet().removeIf(entry -> entry.getValue()[1] <= now);
        return state[0] > ipMaxAttempts;
    }

    private void pruneLocal() {
        if (localFailures.size() < LOCAL_CAPACITY) {
            return;
        }
        long now = System.currentTimeMillis();
        localLockUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        localFailures.clear();
        log.warn("Local login failure cache exceeded {} entries and was reset", LOCAL_CAPACITY);
    }

    private String formatMinutes(Duration remaining) {
        long minutes = Math.max(1L, (remaining.toSeconds() + 59) / 60);
        return minutes + " 分钟";
    }
}
