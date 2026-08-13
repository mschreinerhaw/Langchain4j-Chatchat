package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.uiartifact.TrendSemanticConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UiDisplayConfigControllerTest {

    private TrendSemanticConfigService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TrendSemanticConfigService.class);
        mockMvc = standaloneSetup(new UiDisplayConfigController(service)).build();
    }

    @Test
    void readsConfigurationForAuthenticatedTenant() throws Exception {
        when(service.get("tenant-a")).thenReturn(config("GLOBAL"));

        mockMvc.perform(get("/api/v1/ui-display/trend-semantics")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope").value("GLOBAL"))
            .andExpect(jsonPath("$.data.keywords[0]").value("盈亏"));
    }

    @Test
    void updatesConfigurationForAuthenticatedTenant() throws Exception {
        when(service.update(eq("tenant-a"), any())).thenReturn(config("TENANT"));

        mockMvc.perform(put("/api/v1/ui-display/trend-semantics")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keywords\":[\"盈亏\"],\"upColor\":\"#e5484d\",\"downColor\":\"#16a36a\",\"neutralColor\":\"#98a2b3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope").value("TENANT"));
    }

    private TrendSemanticConfigService.TrendSemanticConfig config(String scope) {
        return new TrendSemanticConfigService.TrendSemanticConfig(
            1, 2, scope, List.of("盈亏"), "#e5484d", "#16a36a", "#98a2b3", Instant.now()
        );
    }
}
