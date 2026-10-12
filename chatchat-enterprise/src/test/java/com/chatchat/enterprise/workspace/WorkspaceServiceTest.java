package com.chatchat.enterprise.workspace;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.repository.identity.*;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceServiceTest {
    WorkspaceRepository rows; WorkspacePreferenceRepository prefs; EnterpriseAdminService admin;
    WorkspaceService service; Map<String, WorkspaceEntity> store; SysTenantRepository tenants;
    EnterpriseAdminService.UserView user;
    @BeforeEach void setup() {
        rows = mock(WorkspaceRepository.class); prefs = mock(WorkspacePreferenceRepository.class);
        admin = mock(EnterpriseAdminService.class); tenants = mock(SysTenantRepository.class); store = new LinkedHashMap<>();
        var resources = mock(ResourceAuthorizationPort.class);
        when(resources.explicitlyAllowedIds(anyString(), anyString(), anyString(), anySet(), anySet())).thenReturn(Set.of());
        service = new WorkspaceService(rows, prefs, tenants, mock(SysMenuRepository.class), mock(SysPermissionRepository.class), admin,
            mock(SysAuditLogRepository.class), new ObjectMapper(), resources);
        user = mock(EnterpriseAdminService.UserView.class);
        when(user.id()).thenReturn("user-A"); when(user.tenantId()).thenReturn("tenant-A"); when(user.status()).thenReturn("enabled");
        when(user.permissionCodes()).thenReturn(List.of()); when(admin.getUserView("user-A")).thenReturn(user);
        var tenant = new SysTenant(); tenant.setId("tenant-A"); tenant.setStatus("enabled");
        when(tenants.lockById("tenant-A")).thenReturn(Optional.of(tenant));
        when(admin.listAuthorizedMenus("user-A")).thenReturn(List.of(new EnterpriseAdminService.MenuNode("chat", "对话", "#chat", "", "menu", List.of())));
        when(rows.findByTenantIdAndWorkspaceId(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0) + "/" + i.getArgument(1))));
        when(rows.findByTenantIdOrderBySortOrderAscWorkspaceIdAsc(anyString())).thenAnswer(i -> store.values().stream().filter(r -> r.getTenantId().equals(i.getArgument(0))).toList());
        when(rows.save(any())).thenAnswer(i -> { WorkspaceEntity r = i.getArgument(0); store.put(r.getTenantId() + "/" + r.getWorkspaceId(), r); return r; });
        when(rows.saveAndFlush(any())).thenAnswer(i -> { WorkspaceEntity r = i.getArgument(0); r.setRevision(r.getRevision() + 1); store.put(r.getTenantId() + "/" + r.getWorkspaceId(), r); return r; });
        when(prefs.findByTenantIdAndUserId(anyString(), anyString())).thenReturn(Optional.empty());
    }
    @Test void legacyMenuIsPreservedAndScienceDoesNotGrantItselfAccess() {
        assertThat(service.available("user-A")).singleElement().satisfies(v -> {
            assertThat(v.manifest().workspaceId()).isEqualTo("zhice");
            assertThat(v.manifest().pages()).extracting(WorkspaceManifest.Page::viewId).containsExactly("chat");
        });
        assertThatThrownBy(() -> service.accessible("user-A", "data-science")).isInstanceOf(WorkspaceService.AccessDenied.class);
        assertThatThrownBy(() -> service.accessible("user-A", "other-tenant-space")).isInstanceOf(WorkspaceService.AccessDenied.class);
    }
    @Test void authorizedPythonUserCanUseScienceWithoutReceivingAnyNewAssetGrant() {
        when(user.permissionCodes()).thenReturn(List.of("capability:data-science"));
        when(admin.listAuthorizedMenus("user-A")).thenReturn(List.of(new EnterpriseAdminService.MenuNode("dataScienceDevelop", "开发", "#dataScienceDevelop", "", "menu", List.of())));
        var manifest = service.accessible("user-A", "data-science");
        assertThat(manifest.pages()).extracting(WorkspaceManifest.Page::viewId).containsExactly("dataScienceDevelop");
        assertThat(manifest.entryRoute()).isEqualTo("#/w/data-science/python/develop");
        assertThat(manifest.defaultSkills()).isEmpty();
    }
    @Test void disabledAndRevokedSpacesCannotBeRecoveredFromPreferences() {
        service.available("user-A");
        var p = new WorkspacePreference(); p.setLastWorkspaceId("data-science"); p.setDefaultWorkspaceId("data-science");
        when(prefs.findByTenantIdAndUserId("tenant-A", "user-A")).thenReturn(Optional.of(p));
        assertThat(service.preference("user-A").effectiveWorkspaceId()).isEqualTo("zhice");
        store.get("tenant-A/zhice").setEnabled(false);
        assertThat(service.available("user-A")).isEmpty();
        assertThat(service.preference("user-A").effectiveWorkspaceId()).isNull();
        assertThatThrownBy(() -> service.setPreference("user-A", null, "zhice", 0)).isInstanceOf(WorkspaceService.AccessDenied.class);
    }
    @Test void managementRequiresServerPermissionAndExistingDefaultCannotDisappear() {
        assertThatThrownBy(() -> service.all("user-A")).isInstanceOf(WorkspaceService.AccessDenied.class);
        when(user.permissionCodes()).thenReturn(List.of("system:admin:operate"));
        service.all("user-A");
        var row = store.get("tenant-A/zhice");
        var m = service.all("user-A").get(0).manifest();
        assertThatThrownBy(() -> service.save("user-A", "zhice", new WorkspaceService.Update(m, false, false, null, row.getRevision()), false))
            .isInstanceOf(WorkspaceService.Conflict.class);
        assertThatThrownBy(() -> service.save("user-A", "zhice", new WorkspaceService.Update(m, true, true, null, 999), false))
            .isInstanceOf(WorkspaceService.Conflict.class);
    }
    @Test void manifestRejectsExternalEntrypointsUnknownComponentsAndDuplicateRoutes() {
        var page = new WorkspaceManifest.Page("chat", "chat", "对话", "chat", "chat");
        var m = new WorkspaceManifest("workspace-manifest.v1", "test", "test", "", "", "1", "1", "https://external", "default", List.of(page), List.of(), List.of());
        assertThatThrownBy(m::validate).isInstanceOf(IllegalArgumentException.class);
        var duplicate = new WorkspaceManifest("workspace-manifest.v1", "test", "test", "", "", "1", "1", "#/w/test/chat", "default", List.of(page, page), List.of(), List.of());
        assertThatThrownBy(duplicate::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
