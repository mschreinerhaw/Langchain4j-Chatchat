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
}
