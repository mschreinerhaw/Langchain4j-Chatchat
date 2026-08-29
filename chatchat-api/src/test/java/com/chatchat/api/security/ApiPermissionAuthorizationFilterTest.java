package com.chatchat.api.security;

import com.chatchat.enterprise.entity.identity.SysPermission;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiPermissionAuthorizationFilterTest {

    @Mock private EnterpriseAdminService adminService;
    @Mock private FilterChain chain;

    private ApiPermissionAuthorizationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiPermissionAuthorizationFilter(adminService, new ObjectMapper());
    }

    @Test
    void deniesDataScienceApiWithoutTheDedicatedPermission() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(adminService.listPermissions()).thenReturn(List.of(dataSciencePermission()));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("capability:library")));

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsDataScienceApiWithTheDedicatedPermission() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(adminService.listPermissions()).thenReturn(List.of(dataSciencePermission()));
        when(adminService.getUserView("user-1")).thenReturn(user(List.of("capability:data-science")));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/v1/data-science/python/workbench");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, "user-1");
        return request;
    }

    private SysPermission dataSciencePermission() {
        SysPermission permission = new SysPermission();
        permission.setPermissionCode("capability:data-science");
        permission.setPermissionType("menu");
        permission.setResourcePath("/api/v1/data-science/python/**");
        permission.setHttpMethod("*");
        permission.setStatus("enabled");
        return permission;
    }

    private EnterpriseAdminService.UserView user(List<String> permissionCodes) {
        return new EnterpriseAdminService.UserView(
            "user-1", "tenant-1", 100001L, "Tenant", null, "alice", "Alice", null, null,
            "enabled", null, List.of("role-1"), permissionCodes, Instant.now(), Instant.now(), false
        );
    }
}
