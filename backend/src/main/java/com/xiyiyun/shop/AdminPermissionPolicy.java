package com.xiyiyun.shop;

import com.xiyiyun.shop.mvp.AdminProfile;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 管理端接口鉴权策略（批次5 / B3：fail-open → fail-closed）。
 *
 * <p><b>原缺陷</b>：{@code requiredPermission} 末尾兜底 {@code return "goods:manage";}，
 * 任何未登记的 {@code /api/admin/**} 新接口只要持有商品管理权限即可访问 —— 典型 fail-open。
 * 新增接口忘记登记时，权限校验静默失效，且没有任何告警。
 *
 * <p><b>修法</b>：
 * <ol>
 *   <li>把「一级资源段 → 权限点」做成显式注册表 {@link #PERMISSIONS}，逐个登记；</li>
 *   <li>兜底改为 {@link #DENY_ALL}（拒绝一切，含超级管理员），未登记即 403；</li>
 *   <li>命中兜底时 WARN 日志，便于上线后立刻发现漏登记的新接口。</li>
 * </ol>
 *
 * <p>权限点沿用系统既有的 6 个（dashboard:read / goods:manage / orders:manage /
 * users:manage / settings:manage / staff:manage）。不新造权限字符串的原因：
 * 前端 StaffView 的权限勾选项与 AdminLayout 导航是硬编码这 6 个值，
 * 新造字符串必须同步改前端并迁移已有员工权限数据。
 */
@Component
public class AdminPermissionPolicy {
    private static final Logger log = LoggerFactory.getLogger(AdminPermissionPolicy.class);
    private static final String ADMIN_API_PREFIX = "/api/admin";
    /** 兜底权限点：不在 ALL_ADMIN_PERMISSIONS 内，任何人都不可能持有 → 未登记接口一律 403。 */
    static final String DENY_ALL = "__deny__:unregistered-endpoint";

    /**
     * 一级资源段 → 所需权限点。key 不带前导斜杠。
     *
     * <p>值为 {@code null} 表示该资源段无需权限（仅需通过 token 校验）。
     */
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
        // 登录态自助接口：/auth/me、/auth/logout、/auth/credentials 等。
        // /auth/credentials 在 InMemoryShopRepository.updateSuperAdminCredentials 内部
        // 自行校验 operator.id()==1，故此处无需额外权限点。
        Map.entry("auth", ""),

        // ---- goods:manage：商品与其供给侧（分类 / 卡种 / 充值字段 / 价格模板 / 供应商 / 对接） ----
        Map.entry("goods", "goods:manage"),
        Map.entry("goods-monitor", "goods:manage"),
        Map.entry("categories", "goods:manage"),
        Map.entry("card-kinds", "goods:manage"),
        Map.entry("recharge-fields", "goods:manage"),
        Map.entry("price-templates", "goods:manage"),
        Map.entry("suppliers", "goods:manage"),
        Map.entry("source-connect", "goods:manage"),
        Map.entry("outbound-protocols", "goods:manage"),
        Map.entry("uploads", "goods:manage"),

        // ---- orders:manage：订单 / 支付流水 / 退款 ----
        Map.entry("orders", "orders:manage"),
        Map.entry("payments", "orders:manage"),
        Map.entry("refunds", "orders:manage"),

        // ---- users:manage：会员、会员分组、会员 API 凭据与调用日志 ----
        Map.entry("users", "users:manage"),
        Map.entry("user-groups", "users:manage"),
        Map.entry("member-api-credentials", "users:manage"),
        Map.entry("open-api-logs", "users:manage"),

        // ---- settings:manage：系统设置、短信登录、人机验证、支付通道 ----
        Map.entry("settings", "settings:manage"),
        Map.entry("wecom-robot", "settings:manage"),
        Map.entry("sms-login-settings", "settings:manage"),
        Map.entry("captcha-settings", "settings:manage"),
        Map.entry("payment-channels", "settings:manage"),

        // ---- staff:manage：后台员工与其权限 ----
        Map.entry("staff", "staff:manage"),

        // ---- dashboard:read：只读日志 ----
        Map.entry("sms-logs", "dashboard:read"),
        Map.entry("operation-logs", "dashboard:read")
    );

    public boolean isAllowed(AdminProfile profile, HttpServletRequest request) {
        String requiredPermission = requiredPermission(request.getServletPath());
        if (requiredPermission == null) {
            return true;
        }
        if (DENY_ALL.equals(requiredPermission)) {
            return false;
        }
        List<String> permissions = profile.permissions();
        return permissions != null && permissions.contains(requiredPermission);
    }

    /**
     * @return {@code null} 表示无需权限；{@link #DENY_ALL} 表示未登记（拒绝）；否则为所需权限点。
     */
    String requiredPermission(String path) {
        if (path == null || !path.startsWith(ADMIN_API_PREFIX)) {
            return null;
        }
        String adminPath = path.substring(ADMIN_API_PREFIX.length());
        if (adminPath.isBlank() || "/".equals(adminPath)) {
            return null;
        }
        if (!adminPath.startsWith("/")) {
            // /api/adminXXX 这类前缀粘连路径不属于管理端接口树，交给后续过滤器/404 处理。
            return null;
        }
        String segment = firstSegment(adminPath);
        if (segment.isEmpty()) {
            return DENY_ALL;
        }
        String permission = PERMISSIONS.get(segment);
        if (permission == null) {
            log.warn("Unregistered admin endpoint denied by fail-closed policy: path={}", path);
            return DENY_ALL;
        }
        return permission.isEmpty() ? null : permission;
    }

    private String firstSegment(String adminPath) {
        int start = 1;
        int end = adminPath.indexOf('/', start);
        String segment = end < 0 ? adminPath.substring(start) : adminPath.substring(start, end);
        int matrix = segment.indexOf(';');
        if (matrix >= 0) {
            segment = segment.substring(0, matrix);
        }
        return segment.toLowerCase(java.util.Locale.ROOT);
    }
}
