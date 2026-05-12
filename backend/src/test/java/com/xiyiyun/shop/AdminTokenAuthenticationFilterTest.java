package com.xiyiyun.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.mvp.AdminProfile;
import com.xiyiyun.shop.mvp.InMemoryShopRepository;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminTokenAuthenticationFilterTest {
    private final InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
    private final AdminTokenAuthenticationFilter filter = new AdminTokenAuthenticationFilter(
        repository,
        new ObjectMapper(),
        new AdminPermissionPolicy()
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminApiWithoutTokenReturns401AndDoesNotContinueChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/orders");
        request.setServletPath("/api/admin/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(repository.findAdminByToken(null)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminApiWithInsufficientPermissionReturns403AndDoesNotContinueChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/settings");
        request.setServletPath("/api/admin/settings");
        request.addHeader("Authorization", "Bearer limited-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(repository.findAdminByToken("Bearer limited-token")).thenReturn(Optional.of(
            new AdminProfile(7L, "limited", "Limited", List.of("dashboard:read"))
        ));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("forbidden");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }
}
