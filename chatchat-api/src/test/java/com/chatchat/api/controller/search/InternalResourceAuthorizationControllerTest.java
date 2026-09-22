package com.chatchat.api.controller.search;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.ResourceAuthorizationRequest;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalResourceAuthorizationControllerTest {
    @Test
    void resolvesCurrentGrantsUsingCanonicalUserRoles() {
        ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("user-a")).thenReturn(user("user-a", "tenant-a", "enabled"));
        when(grants.allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a",
            Set.of("role-a"), Set.of("doc-a", "doc-b"))).thenReturn(Set.of("doc-a"));

        var response = new InternalResourceAuthorizationController(grants, admin).authorize(
            new ResourceAuthorizationRequest("tenant-a", "user-a", ResourceAuthorizationPort.KNOWLEDGE,
                Set.of("doc-a", "doc-b")));

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().allowedIds()).containsExactly("doc-a");
        verify(grants).allowedIds(ResourceAuthorizationPort.KNOWLEDGE, "tenant-a", "user-a",
            Set.of("role-a"), Set.of("doc-a", "doc-b"));
    }

    @Test
    void rejectsCrossTenantPrincipalBeforeGrantLookup() {
        ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
        EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
        when(admin.getUserView("user-a")).thenReturn(user("user-a", "tenant-a", "enabled"));

        var response = new InternalResourceAuthorizationController(grants, admin).authorize(
            new ResourceAuthorizationRequest("tenant-b", "user-a", ResourceAuthorizationPort.KNOWLEDGE,
                Set.of("doc-a")));

        assertThat(response.getCode()).isEqualTo(403);
        verifyNoInteractions(grants);
    }

    private EnterpriseAdminService.UserView user(String id, String tenant, String status) {
        return new EnterpriseAdminService.UserView(id, tenant, 100001L, null, id, id, null, null,
            status, null, List.of("role-a"), List.of(), null, null);
    }
}
