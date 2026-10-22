package com.chatchat.api.datascience.python;

import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PythonDefaultEnvironmentServiceTest {
    private final PythonAssetRepository assets = mock(PythonAssetRepository.class);
    private final PythonEnvironmentPreferenceRepository preferences = mock(PythonEnvironmentPreferenceRepository.class);
    private final McpPythonControlPlanePort python = mock(McpPythonControlPlanePort.class);
    private final PythonDefaultEnvironmentService service = new PythonDefaultEnvironmentService(assets, preferences, python);

    @Test void authenticatedTaskUserIdResolvesWorkshopUsernameWithoutCrossingTenants() {
        var identities = mock(com.chatchat.enterprise.service.EnterpriseAdminService.class);
        var user = mock(com.chatchat.enterprise.service.EnterpriseAdminService.UserView.class);
        when(identities.getUserView("user-id")).thenReturn(user);
        when(user.tenantId()).thenReturn("tenant"); when(user.username()).thenReturn("owner");
        service.setIdentities(identities);
        when(assets.findByTenantIdAndOwnerIdOrderByCreatedAtDesc("tenant", "owner"))
            .thenReturn(List.of(asset("chosen", "published")));
        when(python.environment("published")).thenReturn(environment(5, "image", "PUBLISHED"));
        assertThat(service.resolveForTask("tenant", "user-id", "legacy").assetId()).isEqualTo("chosen");
        assertThatThrownBy(() -> service.resolveForTask("another-tenant", "user-id", "legacy"))
            .isInstanceOf(SecurityException.class);
        verify(assets, never()).findByTenantIdAndOwnerIdOrderByCreatedAtDesc("another-tenant", "owner");
    }

    @Test void workshopSelectionOverridesOldDeploymentEnvironmentAndFollowsPublishedVersions() {
        var asset = asset("asset", "published");
        when(assets.findByTenantIdAndOwnerIdOrderByCreatedAtDesc("tenant", "owner")).thenReturn(List.of(asset));
        when(assets.findByIdAndTenantIdAndOwnerId("asset", "tenant", "owner")).thenReturn(Optional.of(asset));
        when(python.environment("published")).thenReturn(environment(5, "new-image", "PUBLISHED"));
        var selected = service.resolve("tenant", "owner", "old-deployment-environment");
        assertThat(selected.environment().versionNumber()).isEqualTo(5);
        assertThat(selected.environment().dockerImage()).isEqualTo("new-image");
        when(python.environment("published")).thenReturn(environment(6, "next-image", "PUBLISHED"));
        assertThat(service.resolve("tenant", "owner", "old-deployment-environment").environment().dockerImage()).isEqualTo("next-image");
        verify(python, never()).environment("old-deployment-environment");
    }

    @Test void explicitDefaultIsPersistedWithTenantAndOwnerAndCannotSelectAnotherUsersAsset() {
        var asset = asset("selected", "published");
        when(assets.findByIdAndTenantIdAndOwnerId("selected", "tenant", "owner")).thenReturn(Optional.of(asset));
        when(python.environment("published")).thenReturn(environment(5, "new-image", "PUBLISHED"));
        service.setDefault("tenant", "owner", "selected");
        var saved = org.mockito.ArgumentCaptor.forClass(PythonEnvironmentPreference.class);
        verify(preferences).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("tenant");
        assertThat(saved.getValue().getOwnerId()).isEqualTo("owner");
        assertThat(saved.getValue().getAssetId()).isEqualTo("selected");
        assertThatThrownBy(() -> service.setDefault("tenant", "other-owner", "selected"))
            .hasMessageContaining("不属于当前用户");
        verify(preferences, times(1)).saveAndFlush(any());
    }

    @Test void multipleAssetsRequireAnExplicitChoiceInsteadOfUsingOldStaticConfiguration() {
        when(assets.findByTenantIdAndOwnerIdOrderByCreatedAtDesc("tenant", "owner"))
            .thenReturn(List.of(asset("one", "first"), asset("two", "second")));
        assertThatThrownBy(() -> service.resolve("tenant", "owner", "legacy"))
            .hasMessageContaining("设置默认环境");
        verifyNoInteractions(python);
    }

    @Test void draftOrDisabledDefaultsAndUnavailableControlPlanesDoNotAdmitWork() {
        var asset = asset("asset", "published");
        when(assets.findByIdAndTenantIdAndOwnerId("asset", "tenant", "owner")).thenReturn(Optional.of(asset));
        when(python.environment("published")).thenReturn(environment(5, "image", "DRAFT"));
        assertThatThrownBy(() -> service.setDefault("tenant", "owner", "asset")).hasMessageContaining("尚未发布或已停用");
        when(python.environment("published")).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.setDefault("tenant", "owner", "asset")).hasMessageContaining("unavailable");
        verify(preferences, never()).saveAndFlush(any());
    }

    @Test void persistedDefaultRemainsSelectedWhenAnotherAssetIsAdded() {
        var asset = asset("chosen", "published");
        var preference = new PythonEnvironmentPreference(); preference.setAssetId("chosen");
        when(preferences.findById(anyString())).thenReturn(Optional.of(preference));
        when(assets.findByIdAndTenantIdAndOwnerId("chosen", "tenant", "owner")).thenReturn(Optional.of(asset));
        when(python.environment("published")).thenReturn(environment(5, "image", "PUBLISHED"));
        assertThat(service.resolve("tenant", "owner", "legacy").assetId()).isEqualTo("chosen");
    }

    static PythonAssetEntity asset(String id, String environment) {
        var asset = new PythonAssetEntity(); asset.setId(id); asset.setMcpEnvironmentId(environment);
        asset.setMcpEnvironmentVersion(4); asset.setDockerImage("stale-image"); return asset;
    }
    static McpPythonControlPlanePort.EnvironmentView environment(int version, String image, String status) {
        return new McpPythonControlPlanePort.EnvironmentView("published", "Python", "", image, "3.11", "1", "512m",
            "1g", "64m", "10001:10001", "NONE", null, "[]", 300, false, version, status);
    }
}
