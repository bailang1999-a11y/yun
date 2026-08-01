package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import com.xiyiyun.shop.security.LoginAttemptGuard;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 批次5 / B2：登录暴力破解防护。
 *
 * <p>覆盖验收：连续失败达阈值后锁定、锁定期内即使密码正确也拒、
 * 不存在账号与密码错误的响应<b>完全不可区分</b>（文案 + 异常类型 + 都走等价开销哈希）。
 */
class LoginBruteForceProtectionTest {
    private static final String KNOWN_ACCOUNT = "13800000001";
    private static final String KNOWN_PASSWORD = "Correct-Password-1";
    private static final String UNKNOWN_ACCOUNT = "13900009999";
    private static final String ADMIN_ACCOUNT = "admin";
    private static final String ADMIN_PASSWORD = "Admin-Password-1";

    @Test
    void memberLoginLocksAccountAfterThresholdFailures() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(3));
        seedUserPassword(repository, KNOWN_ACCOUNT, KNOWN_PASSWORD);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "wrong-password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号或密码不正确");
        }

        // 第 4 次：即使密码正确也必须被拒（锁定生效）
        assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, KNOWN_PASSWORD)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("账号已被临时锁定");
    }

    @Test
    void successfulLoginClearsFailureCounter() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(3));
        seedUserPassword(repository, KNOWN_ACCOUNT, KNOWN_PASSWORD);

        assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "wrong-password")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "wrong-password")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, KNOWN_PASSWORD)))
            .doesNotThrowAnyException();

        // 计数已清零：再连错 2 次仍不该锁
        assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "wrong-password")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("账号或密码不正确");
        assertThatThrownBy(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "wrong-password")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("账号或密码不正确");
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(50));
        seedUserPassword(repository, KNOWN_ACCOUNT, KNOWN_PASSWORD);

        Throwable unknownAccount = catchThrowable(() -> repository.loginUser(loginRequest(UNKNOWN_ACCOUNT, "any-password")));
        Throwable wrongPassword = catchThrowable(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "any-password")));

        assertThat(unknownAccount).isNotNull();
        assertThat(wrongPassword).isNotNull();
        assertThat(unknownAccount.getClass()).isEqualTo(wrongPassword.getClass());
        assertThat(unknownAccount.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownAccount.getMessage()).isEqualTo("账号或密码不正确");
    }

    /**
     * 时间侧信道：不存在账号也要跑一次等价开销的 BCrypt。
     *
     * <p>不断言绝对耗时（CI 上不稳定），而是断言两条路径耗时<b>同一量级</b>：
     * 不存在账号的耗时不得低于密码错误耗时的 1/4。修复前不存在账号是纯查表直接返回，
     * 与一次 cost=10 的 BCrypt（数十毫秒）相差两个数量级，这条断言必然失败。
     */
    @Test
    void unknownAccountBurnsComparablePasswordWork() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(500));
        seedUserPassword(repository, KNOWN_ACCOUNT, KNOWN_PASSWORD);

        // 预热，避免 JIT / 类加载影响首次调用
        catchThrowable(() -> repository.loginUser(loginRequest(UNKNOWN_ACCOUNT, "warmup")));
        catchThrowable(() -> repository.loginUser(loginRequest(KNOWN_ACCOUNT, "warmup")));

        long unknownNanos = medianNanos(repository, UNKNOWN_ACCOUNT);
        long wrongPasswordNanos = medianNanos(repository, KNOWN_ACCOUNT);

        assertThat(unknownNanos)
            .as("不存在账号(%dns)与密码错误(%dns)必须同量级，否则响应时间就是账号枚举侧信道",
                unknownNanos, wrongPasswordNanos)
            .isGreaterThan(wrongPasswordNanos / 4);
    }

    @Test
    void adminLoginLocksAccountAfterThresholdFailures() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(3));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> repository.loginAdmin(loginRequest(ADMIN_ACCOUNT, "wrong-password", "admin"), "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号或密码不正确");
        }

        assertThatThrownBy(() -> repository.loginAdmin(loginRequest(ADMIN_ACCOUNT, ADMIN_PASSWORD, "admin"), "10.0.0.1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("账号已被临时锁定");
    }

    @Test
    void adminLoginUnknownAccountUsesSameMessageAsWrongPassword() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(50));

        Throwable unknownAccount = catchThrowable(
            () -> repository.loginAdmin(loginRequest("no-such-staff", "any", "admin"), "10.0.0.2"));
        Throwable wrongPassword = catchThrowable(
            () -> repository.loginAdmin(loginRequest(ADMIN_ACCOUNT, "any", "admin"), "10.0.0.2"));

        assertThat(unknownAccount.getClass()).isEqualTo(wrongPassword.getClass());
        assertThat(unknownAccount.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownAccount.getMessage()).isEqualTo("账号或密码不正确");
    }

    @Test
    void perIpRateLimitBlocksHorizontalCredentialStuffing() {
        InMemoryShopRepository repository = newRepository();
        // 账号阈值放很高，只让 IP 维度生效：每个账号只试 1 次，横向刷账号
        repository.replaceLoginAttemptGuardForTest(
            new LoginAttemptGuard((RedisSecurityStateStore) null, 500, 900L, 900L, 3600L, 300L, 4));

        for (int i = 0; i < 4; i++) {
            String account = "1390000000" + i;
            assertThatThrownBy(() -> repository.loginUser(loginRequest(account, "any"), "203.0.113.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号或密码不正确");
        }

        assertThatThrownBy(() -> repository.loginUser(loginRequest("13900000099", "any"), "203.0.113.9"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("登录尝试过于频繁");
    }

    @Test
    void adminLoginForcesCaptchaOnceProviderIsConfigured() {
        InMemoryShopRepository repository = newRepository();
        repository.replaceLoginAttemptGuardForTest(guard(50));
        // 后台两个开关都关（这正是原缺陷的默认状态），但凭据齐备
        configureTencentCaptchaWithoutEnablingSwitches(repository);

        assertThatThrownBy(() -> repository.loginAdmin(loginRequest(ADMIN_ACCOUNT, ADMIN_PASSWORD, "admin"), "10.0.0.3"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("请先完成人机验证");

        assertThat(repository.captchaChallenge("admin").enabled())
            .as("管理端强制验证码后，challenge 必须告诉前端需要渲染验证码")
            .isTrue();
    }

    // ================================================================= helpers

    private static long medianNanos(InMemoryShopRepository repository, String account) {
        long[] samples = new long[3];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            catchThrowable(() -> repository.loginUser(loginRequest(account, "any-password")));
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        return samples[1];
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private static LoginAttemptGuard guard(int maxFailures) {
        // securityStateStore=null → 走进程内计数，单测不依赖 Redis；IP 上限调高以隔离账号维度
        return new LoginAttemptGuard((RedisSecurityStateStore) null, maxFailures, 900L, 900L, 3600L, 300L, 100_000);
    }

    private static LoginRequest loginRequest(String account, String password) {
        return loginRequest(account, password, "h5");
    }

    private static LoginRequest loginRequest(String account, String password, String terminal) {
        return new LoginRequest(account, password, "", terminal, "", "", "");
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            ADMIN_ACCOUNT,
            new BCryptPasswordEncoder().encode(ADMIN_PASSWORD),
            "Admin"
        );
    }

    private static void seedUserPassword(InMemoryShopRepository repository, String mobile, String rawPassword) {
        Long userId = allUsers(repository).values().stream()
            .filter(user -> mobile.equals(user.mobile()))
            .map(UserItem::id)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("seed user not found: " + mobile));
        passwordHashes(repository).put(userId, new BCryptPasswordEncoder().encode(rawPassword));
    }

    private static void configureTencentCaptchaWithoutEnablingSwitches(InMemoryShopRepository repository) {
        CaptchaSettingItem current = repository.captchaSetting();
        Map<String, String> tencent = new ConcurrentHashMap<>(current.tencentConfig());
        tencent.put("secret_id", "unit-test-secret-id");
        tencent.put("secret_key", "unit-test-secret-key");
        tencent.put("captcha_app_id", "unit-test-app-id");
        tencent.put("app_secret_key", "unit-test-app-secret");
        setAuthServiceField(repository, "captchaSetting", new CaptchaSettingItem(
            false, false, false, false, "TENCENT",
            Map.copyOf(tencent), current.turnstileConfig(), current.genericConfig(), Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, UserItem> allUsers(InMemoryShopRepository repository) {
        return (Map<Long, UserItem>) readUserServiceField(repository, "users");
    }

    /**
     * 批次7C / 任务C1：users 已随会员域搬到 {@link UserService}
     * （userPasswordHashes 属登录域，批次8D 起在 {@link AuthService}，见下面的
     * {@link #readAuthServiceField}）。只机械改反射目标，断言逐字未变。
     */
    private static Object readUserServiceField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("userService");
            holder.setAccessible(true);
            Object userService = holder.get(repository);
            Field field = userService.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(userService);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, String> passwordHashes(InMemoryShopRepository repository) {
        return (Map<Long, String>) readAuthServiceField(repository, "userPasswordHashes");
    }

    /**
     * 批次8D / 任务D1：userPasswordHashes / captchaSetting 已随登录域搬到 {@link AuthService}。
     * 与 {@link #readUserServiceField} 同一手法，只机械改反射目标，断言逐字未变。
     */
    private static Object readAuthServiceField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("authService");
            holder.setAccessible(true);
            Object authService = holder.get(repository);
            Field field = authService.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(authService);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setAuthServiceField(InMemoryShopRepository repository, String name, Object value) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("authService");
            holder.setAccessible(true);
            Object authService = holder.get(repository);
            Field field = authService.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(authService, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
