package com.chatchat.enterprise.workspace;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.entity.identity.SysTenant;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceResourceServiceTest {
    final WorkspaceResourceRepository repository = mock(WorkspaceResourceRepository.class);
    final EnterpriseAdminService admin = mock(EnterpriseAdminService.class);
    final SysTenantRepository tenants = mock(SysTenantRepository.class);
    final WorkspaceResourceService service = new WorkspaceResourceService(repository, admin, tenants);
    WorkspaceRequestContext.Scope scope(String user, String space) { return new WorkspaceRequestContext.Scope("tenant", user, space); }
    WorkspaceResourceBinding binding() {
        var row = new WorkspaceResourceBinding(); row.setTenantId("tenant"); row.setResourceType("task"); row.setResourceId("id");
        row.setOwnerId("owner"); row.setOriginWorkspaceId("science"); return row;
    }
    @Test void headerOmissionOtherWorkspaceAndOtherUserCannotReadPrivateObject() {
        when(repository.findByTenantIdAndResourceTypeAndResourceId("tenant", "task", "id")).thenReturn(Optional.of(binding()));
        assertThatCode(() -> service.assertAccess(scope("owner", "science"), "task", "id")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertAccess(scope("owner", "zhice"), "task", "id")).isInstanceOf(WorkspaceService.AccessDenied.class);
        assertThatThrownBy(() -> service.assertAccess(scope("other", "science"), "task", "id")).isInstanceOf(WorkspaceService.AccessDenied.class);
    }
    @Test void manifestOrSingleAdministratorPermissionCannotGrantCrossWorkspaceAccess() {
        when(repository.findByTenantIdAndResourceTypeAndResourceId("tenant", "task", "id")).thenReturn(Optional.of(binding()));
        when(admin.hasPermission("operator", "system:admin:operate")).thenReturn(true);
        assertThatThrownBy(() -> service.assertAccess(scope("operator", "zhice"), "task", "id")).isInstanceOf(WorkspaceService.AccessDenied.class);
        when(admin.hasPermission("operator", "platform:tasks:all")).thenReturn(true);
        assertThatCode(() -> service.assertAccess(scope("operator", "zhice"), "task", "id")).doesNotThrowAnyException();
    }
    @Test void legacyObjectsRemainInZhiceAndNeverAppearInNewWorkspace() {
        when(repository.findByTenantIdAndResourceTypeAndResourceId(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThatCode(() -> service.assertAccess(scope("owner", "zhice"), "session", "legacy")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertAccess(scope("owner", "science"), "session", "legacy")).isInstanceOf(WorkspaceService.AccessDenied.class);
    }
    @Test void duplicateSubmissionNeverChangesOriginOrOwnerAndCannotClaimAnotherTenant() {
        when(tenants.lockById("tenant")).thenReturn(Optional.of(new SysTenant()));
        when(repository.findByTenantIdAndResourceTypeAndResourceId("tenant", "task", "id")).thenReturn(Optional.of(binding()));
        assertThatCode(() -> service.bind("tenant", "task", "id", scope("owner", "science"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.bind("tenant", "task", "id", scope("owner", "zhice"))).isInstanceOf(WorkspaceService.AccessDenied.class);
        assertThatThrownBy(() -> service.bind("another-tenant", "task", "id", scope("owner", "science"))).isInstanceOf(WorkspaceService.AccessDenied.class);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void artifactInheritsTaskOriginWithoutUsingCurrentBrowserWorkspace() {
        when(tenants.lockById("tenant")).thenReturn(Optional.of(new SysTenant()));
        when(repository.findByTenantIdAndResourceTypeAndResourceId("tenant", "task", "id")).thenReturn(Optional.of(binding()));
        when(repository.findByTenantIdAndResourceTypeAndResourceId("tenant", "artifact", "out")).thenReturn(Optional.empty());
        service.inherit("tenant", "task", "id", "artifact", "out");
        var capture = org.mockito.ArgumentCaptor.forClass(WorkspaceResourceBinding.class); verify(repository).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getOriginWorkspaceId()).isEqualTo("science"); assertThat(capture.getValue().getOwnerId()).isEqualTo("owner");
    }
}
