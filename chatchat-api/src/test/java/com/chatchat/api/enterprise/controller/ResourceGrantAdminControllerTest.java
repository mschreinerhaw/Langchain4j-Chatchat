package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResourceGrantAdminControllerTest {
    @Test
    void bindsNamedListParametersWithoutCompilerParameterMetadata() throws Exception {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        when(repository.findByTenantIdAndResourceTypeOrderByUpdatedAtDesc("tenant-a", "KNOWLEDGE"))
            .thenReturn(List.of());
        MockMvc mvc = standaloneSetup(new ResourceGrantAdminController(repository, admin)).build();

        mvc.perform(get("/api/v1/enterprise/resource-grants")
                .requestAttr(ApiAuthenticationFilter.CURRENT_USER_ID, "user-a")
                .param("tenantId", "tenant-a")
                .param("resourceType", "KNOWLEDGE"))
            .andExpect(status().isOk());
    }

    @Test
    void rejectsCrossTenantGrantBeforeWriting() {
        ResourceGrantRepository repository = mock(ResourceGrantRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-a");
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        ResourceGrant grant = new ResourceGrant();
        grant.setTenantId("tenant-b");
        grant.setResourceType("SKILL");
        grant.setResourceId("skill-1");
        grant.setPrincipalType("TENANT");
        grant.setPrincipalId("tenant-b");
        grant.setEffect("ALLOW");

        assertThatThrownBy(() -> new ResourceGrantAdminController(repository, admin).create(request, grant))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cross-tenant");
        verifyNoInteractions(repository);
    }
}
