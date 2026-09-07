package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.analysis.profile.DomainAnalysisProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DomainAnalysisProfileControllerTest {
    @Test void maintenanceUsesAuthenticatedTenantInsteadOfClientSuppliedTenant() throws Exception {
        var service = mock(DomainAnalysisProfileService.class);
        when(service.list("tenant-a")).thenReturn(List.of());
        var mvc = standaloneSetup(new DomainAnalysisProfileController(service)).build();
        mvc.perform(get("/api/v1/analysis-profiles").param("tenantId", "tenant-b")
            .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/analysis-profiles/RETAIL").requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"零售\",\"description\":\"门店表现\",\"enabled\":true,\"guidance\":{\"focus\":[\"门店\"]}}"))
            .andExpect(status().isOk());
        verify(service).list("tenant-a");
        verify(service).update(eq("tenant-a"), eq("RETAIL"), any());
        verify(service, never()).list("tenant-b");
    }
}
