package com.chatchat.mcpserver.license;

import com.chatchat.license.LicensePayload;
import com.chatchat.license.LicenseStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LicenseInternalControllerTest {

    @Test
    void codingDispatchReflectsLicenseRenewalWithoutCachedAdmission() {
        var service = mock(McpLicenseService.class);
        var controller = new LicenseInternalController(service);
        assertThat(controller.codingDispatchEntitlement().getData().licenseValid()).isFalse();
        when(service.status()).thenReturn(LicenseStatus.invalid("EXPIRED", "expired", "server", null));
        assertThat(controller.codingDispatchEntitlement().getData().licenseStatus()).isEqualTo("EXPIRED");
        assertThat(controller.codingDispatchEntitlement().getData().licenseValid()).isFalse();
        when(service.status()).thenReturn(LicenseStatus.valid("server", null));
        assertThat(controller.codingDispatchEntitlement().getData().licenseValid()).isTrue();
    }

    @Test
    void workspaceEntitlementRespectsExplicitModulesAndLegacyPythonLicense() {
        McpLicenseService service = mock(McpLicenseService.class);
        var legacy = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("pythonManagement"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", legacy));
        var controller = new LicenseInternalController(service);
        assertThat(controller.workspaceEntitlement().getData().workspaceIds()).containsExactlyInAnyOrder("zhice", "data-science");
        var explicit = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("workspaceZhice", "pythonManagement"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", explicit));
        assertThat(controller.workspaceEntitlement().getData().workspaceIds()).containsExactly("zhice");
        var extension = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("workspace:research", "workspace:INVALID/ID"), 100, 7, "*",
            LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", extension));
        assertThat(controller.workspaceEntitlement().getData().workspaceIds()).containsExactly("research");
        when(service.status()).thenReturn(LicenseStatus.invalid("INVALID", "expired", "server", explicit));
        assertThat(controller.workspaceEntitlement().getData().workspaceIds()).isEmpty();
    }

    @Test
    void exposesOnlyAgentPublicationEntitlementFromValidLicense() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("assets"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));

        var response = new LicenseInternalController(service).agentPublicationLimit().getData();

        assertThat(response.licenseValid()).isTrue();
        assertThat(response.limited()).isTrue();
        assertThat(response.maxPublishedAgents()).isEqualTo(7);
    }

    @Test
    void reportsLegacyLicenseWithoutAgentLimitAsUnlimited() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("assets"), 100, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));

        var response = new LicenseInternalController(service).agentPublicationLimit().getData();

        assertThat(response.limited()).isFalse();
        assertThat(response.maxPublishedAgents()).isNull();
    }

    @Test
    void exposesSkillPublicationEntitlementThroughInternalSignedChannelEndpoint() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("assets"), 100, 7, 25, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));

        var response = new LicenseInternalController(service).skillPublicationLimit().getData();

        assertThat(response.licenseValid()).isTrue();
        assertThat(response.limited()).isTrue();
        assertThat(response.maxPublishedSkills()).isEqualTo(25);
    }

    @Test
    void reportsLegacyLicenseWithoutConfiguredSkillLimit() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("assets"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));

        var response = new LicenseInternalController(service).skillPublicationLimit().getData();

        assertThat(response.limited()).isFalse();
        assertThat(response.maxPublishedSkills()).isNull();
    }

    @Test
    void exposesMcpSkillFederationOnlyWhenTheModuleIsLicensed() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("mcpSkillFederation"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));
        when(service.allowsModule("mcpSkillFederation")).thenReturn(true);

        var response = new LicenseInternalController(service).skillFederationEntitlement().getData();

        assertThat(response.allowed()).isTrue();
        assertThat(response.licenseStatus()).isEqualTo("VALID");
    }

    @Test
    void deniesMcpSkillFederationWhenTheModuleIsMissing() {
        McpLicenseService service = mock(McpLicenseService.class);
        LicensePayload payload = new LicensePayload("LIC", "Customer", "C", "LiveMCP", "enterprise",
            List.of("assets"), 100, 7, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
        when(service.status()).thenReturn(LicenseStatus.valid("server", payload));

        var response = new LicenseInternalController(service).skillFederationEntitlement().getData();

        assertThat(response.allowed()).isFalse();
    }
}
