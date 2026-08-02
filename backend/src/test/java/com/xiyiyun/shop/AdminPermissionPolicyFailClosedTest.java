package com.xiyiyun.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.mvp.AdminProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 批次5 / B3：管理端权限兜底从 fail-open 改为 fail-closed。
 *
 * <p>原实现末尾 {@code return "goods:manage";}，未登记接口只要有商品权限就能访问。
 * 现在未登记一律拒绝，且已登记接口的权限点逐条固化在这里防回归。
 */
class AdminPermissionPolicyFailClosedTest {
    private final AdminPermissionPolicy policy = new AdminPermissionPolicy();

    private static final AdminProfile SUPER_ADMIN = new AdminProfile(1L, "admin", "Admin", List.of(
        "dashboard:read", "goods:manage", "orders:manage", "users:manage", "settings:manage", "staff:manage"
    ));

    @Test
    void unregisteredEndpointIsDeniedEvenForSuperAdmin() {
        assertThat(policy.isAllowed(SUPER_ADMIN, get("/api/admin/brand-new-feature"))).isFalse();
        assertThat(policy.isAllowed(SUPER_ADMIN, get("/api/admin/brand-new-feature/42/delete"))).isFalse();
    }

    @Test
    void unregisteredEndpointNoLongerFallsBackToGoodsManage() {
        AdminProfile goodsOnly = new AdminProfile(9L, "goods", "Goods", List.of("goods:manage"));

        assertThat(policy.isAllowed(goodsOnly, get("/api/admin/brand-new-feature"))).isFalse();
        // 对照：已登记的商品接口仍然放行，证明不是把所有请求都拒了
        assertThat(policy.isAllowed(goodsOnly, get("/api/admin/goods"))).isTrue();
    }

    @Test
    void emptySegmentIsDenied() {
        assertThat(policy.requiredPermission("/api/admin//goods")).isEqualTo(AdminPermissionPolicy.DENY_ALL);
    }

    @Test
    void nonAdminPathNeedsNoPermission() {
        assertThat(policy.requiredPermission("/api/h5/goods")).isNull();
        assertThat(policy.requiredPermission("/api/admin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/admin/auth/me",
        "/api/admin/auth/logout",
        "/api/admin/auth/credentials",
        "/api/admin/auth/login",
        "/api/admin/auth/slider",
        "/api/admin/auth/captcha-config",
        "/api/admin/auth/sms/send"
    })
    void authEndpointsNeedNoPermissionPoint(String path) {
        assertThat(policy.requiredPermission(path)).isNull();
    }

    /** 全量「接口 → 权限点」映射表，与报告中的表格一一对应。 */
    @ParameterizedTest
    @CsvSource({
        // ---- goods:manage ----
        "/api/admin/goods,goods:manage",
        "/api/admin/goods/1,goods:manage",
        "/api/admin/goods/1/delete,goods:manage",
        "/api/admin/goods/1/cards,goods:manage",
        "/api/admin/goods/1/cards/import,goods:manage",
        "/api/admin/goods/1/channels,goods:manage",
        "/api/admin/goods/1/channels/2/delete,goods:manage",
        "/api/admin/goods-monitor,goods:manage",
        "/api/admin/goods-monitor/logs,goods:manage",
        "/api/admin/goods-monitor/scan,goods:manage",
        "/api/admin/goods-monitor/channels/2/scan,goods:manage",
        "/api/admin/categories,goods:manage",
        "/api/admin/categories/1,goods:manage",
        "/api/admin/categories/1/enable,goods:manage",
        "/api/admin/categories/1/disable,goods:manage",
        "/api/admin/categories/1/delete,goods:manage",
        "/api/admin/card-kinds,goods:manage",
        "/api/admin/card-kinds/1/cards,goods:manage",
        "/api/admin/card-kinds/1/cards/import,goods:manage",
        "/api/admin/recharge-fields,goods:manage",
        "/api/admin/recharge-fields/1,goods:manage",
        "/api/admin/recharge-fields/1/enable,goods:manage",
        "/api/admin/recharge-fields/1/disable,goods:manage",
        "/api/admin/recharge-fields/1/delete,goods:manage",
        "/api/admin/price-templates,goods:manage",
        "/api/admin/suppliers,goods:manage",
        "/api/admin/suppliers/1,goods:manage",
        "/api/admin/suppliers/1/delete,goods:manage",
        "/api/admin/suppliers/1/enable,goods:manage",
        "/api/admin/suppliers/1/disable,goods:manage",
        "/api/admin/suppliers/1/balance,goods:manage",
        "/api/admin/suppliers/1/test,goods:manage",
        "/api/admin/suppliers/1/sync-goods,goods:manage",
        "/api/admin/suppliers/1/remote-goods,goods:manage",
        "/api/admin/suppliers/1/remote-goods/snapshot,goods:manage",
        "/api/admin/source-connect/suppliers/1/remote-goods,goods:manage",
        "/api/admin/source-connect/suppliers/1/clone,goods:manage",
        "/api/admin/source-connect/benefit-durations/repair,goods:manage",
        "/api/admin/outbound-protocols,goods:manage",
        "/api/admin/uploads/images,goods:manage",
        // ---- orders:manage ----
        "/api/admin/orders,orders:manage",
        "/api/admin/orders/xiyi1,orders:manage",
        "/api/admin/orders/xiyi1/delete,orders:manage",
        "/api/admin/orders/xiyi1/retry,orders:manage",
        "/api/admin/orders/xiyi1/retry-channel/2,orders:manage",
        "/api/admin/orders/xiyi1/refund,orders:manage",
        "/api/admin/orders/xiyi1/complete-manual,orders:manage",
        "/api/admin/orders/xiyi1/manual-success,orders:manage",
        "/api/admin/orders/xiyi1/manual-failed,orders:manage",
        "/api/admin/orders/xiyi1/refresh-callback,orders:manage",
        "/api/admin/orders/export,orders:manage",
        "/api/admin/orders/refresh-unfinished,orders:manage",
        "/api/admin/payments,orders:manage",
        "/api/admin/refunds,orders:manage",
        // ---- users:manage ----
        "/api/admin/users,users:manage",
        "/api/admin/users/1/group,users:manage",
        "/api/admin/users/1/funds,users:manage",
        "/api/admin/users/1/credentials,users:manage",
        "/api/admin/users/1/member-api,users:manage",
        "/api/admin/user-groups,users:manage",
        "/api/admin/user-groups/1/rules,users:manage",
        "/api/admin/user-groups/1/order-permission,users:manage",
        "/api/admin/member-api-credentials,users:manage",
        "/api/admin/open-api-logs,users:manage",
        // ---- settings:manage ----
        "/api/admin/settings,settings:manage",
        "/api/admin/wecom-robot/test,settings:manage",
        "/api/admin/wecom-robot/deliveries,settings:manage",
        "/api/admin/sms-login-settings,settings:manage",
        "/api/admin/captcha-settings,settings:manage",
        "/api/admin/captcha-settings/test,settings:manage",
        "/api/admin/payment-channels,settings:manage",
        "/api/admin/payment-channels/1,settings:manage",
        "/api/admin/payment-channels/1/enable,settings:manage",
        "/api/admin/payment-channels/1/disable,settings:manage",
        "/api/admin/payment-channels/1/delete,settings:manage",
        // ---- staff:manage ----
        "/api/admin/staff,staff:manage",
        "/api/admin/staff/1,staff:manage",
        "/api/admin/staff/1/delete,staff:manage",
        // ---- dashboard:read ----
        "/api/admin/sms-logs,dashboard:read",
        "/api/admin/operation-logs,dashboard:read"
    })
    void everyRegisteredEndpointMapsToItsPermissionPoint(String path, String expectedPermission) {
        assertThat(policy.requiredPermission(path)).isEqualTo(expectedPermission);
    }

    private MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
