package com.xiyiyun.shop;

import com.xiyiyun.shop.mvp.AdminProfile;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AdminPermissionPolicy {
    private static final String ADMIN_API_PREFIX = "/api/admin";

    public boolean isAllowed(AdminProfile profile, HttpServletRequest request) {
        String requiredPermission = requiredPermission(request.getServletPath());
        if (requiredPermission == null) {
            return true;
        }
        List<String> permissions = profile.permissions();
        return permissions != null && permissions.contains(requiredPermission);
    }

    private String requiredPermission(String path) {
        if (path == null || !path.startsWith(ADMIN_API_PREFIX)) {
            return null;
        }

        String adminPath = path.substring(ADMIN_API_PREFIX.length());
        if (adminPath.isBlank() || adminPath.startsWith("/auth/")) {
            return null;
        }
        if (adminPath.equals("/settings") || adminPath.startsWith("/settings/")) {
            return "settings:manage";
        }
        if (adminPath.equals("/users") || adminPath.startsWith("/users/") ||
            adminPath.equals("/user-groups") || adminPath.startsWith("/user-groups/") ||
            adminPath.equals("/member-api-credentials") || adminPath.startsWith("/member-api-credentials") ||
            adminPath.equals("/open-api-logs") || adminPath.startsWith("/open-api-logs")) {
            return "users:manage";
        }
        if (adminPath.equals("/orders") || adminPath.startsWith("/orders/") ||
            adminPath.equals("/payments") || adminPath.startsWith("/payments/") ||
            adminPath.equals("/refunds") || adminPath.startsWith("/refunds/")) {
            return "orders:manage";
        }
        if (adminPath.equals("/sms-logs") || adminPath.startsWith("/sms-logs/") ||
            adminPath.equals("/operation-logs") || adminPath.startsWith("/operation-logs/")) {
            return "dashboard:read";
        }

        return "goods:manage";
    }
}
