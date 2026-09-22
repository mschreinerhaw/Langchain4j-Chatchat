package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillResourceScopeAdminControllerTest {
    @Test
    void tenantAdministratorCannotBindAnotherTenantsDocuments() {
        SkillResourceScopeRepository repository = mock(SkillResourceScopeRepository.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-a");
        when(admin.getUserView("user-a")).thenReturn(new EnterpriseAdminService.UserView(
            "user-a", "tenant-a", 100001L, null, "user-a", "User A", null, null,
            "enabled", null, List.of(), List.of(), null, null));
        SkillResourceScope scope = new SkillResourceScope();
        scope.setTenantId("tenant-b"); scope.setSkillId("agent-skill");
        scope.setResourceType("DOCUMENT"); scope.setResourceId("doc-1");

        assertThatThrownBy(() -> new SkillResourceScopeAdminController(repository, admin).create(request, scope))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cross-tenant");
        verifyNoInteractions(repository);
    }
}
