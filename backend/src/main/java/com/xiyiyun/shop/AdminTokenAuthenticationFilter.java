package com.xiyiyun.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.AdminProfile;
import com.xiyiyun.shop.mvp.InMemoryShopRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminTokenAuthenticationFilter extends OncePerRequestFilter {
    private static final String ADMIN_API_PREFIX = "/api/admin/";
    private static final String ADMIN_LOGIN_PATH = "/api/admin/auth/login";

    private final InMemoryShopRepository repository;
    private final ObjectMapper objectMapper;
    private final AdminPermissionPolicy permissionPolicy;

    public AdminTokenAuthenticationFilter(
        InMemoryShopRepository repository,
        ObjectMapper objectMapper,
        AdminPermissionPolicy permissionPolicy
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.permissionPolicy = permissionPolicy;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!requiresAdminToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        var adminProfile = repository.findAdminByToken(request.getHeader("Authorization"));
        if (adminProfile.isEmpty()) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response);
            return;
        }

        AdminProfile profile = adminProfile.get();
        if (!permissionPolicy.isAllowed(profile, request)) {
            SecurityContextHolder.clearContext();
            writeForbidden(response);
            return;
        }

        authenticate(profile, request);
        filterChain.doFilter(request, response);
    }

    private boolean requiresAdminToken(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        String path = request.getServletPath();
        return path.startsWith(ADMIN_API_PREFIX) && !ADMIN_LOGIN_PATH.equals(path);
    }

    private void authenticate(AdminProfile profile, HttpServletRequest request) {
        var authorities = profile.permissions().stream()
            .map(permission -> new SimpleGrantedAuthority("PERMISSION_" + permission))
            .toList();
        var authentication = new UsernamePasswordAuthenticationToken(profile, null, authorities);
        authentication.setDetails(request);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void writeUnauthorized(HttpServletResponse response) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
    }

    private void writeForbidden(HttpServletResponse response) {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden");
    }

    private void writeError(HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ApiResponse.fail(message));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write admin auth error response", ex);
        }
    }
}
