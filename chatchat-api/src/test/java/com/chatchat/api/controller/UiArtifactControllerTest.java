package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.uiartifact.UiArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UiArtifactControllerTest {

    private UiArtifactService artifactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(UiArtifactService.class);
        mockMvc = standaloneSetup(new UiArtifactController(artifactService)).build();
    }

    @Test
    void bindsArtifactIdWithoutCompilerParameterMetadata() throws Exception {
        when(artifactService.manifest("tenant-a", "ui-1"))
            .thenReturn(Optional.of(Map.of("artifactId", "ui-1")));

        mockMvc.perform(get("/api/v1/ui-artifacts/ui-1")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.artifactId").value("ui-1"));
    }

    @Test
    void bindsArtifactAndResourceIdsWithoutCompilerParameterMetadata() throws Exception {
        when(artifactService.resource("tenant-a", "ui-1", "answer"))
            .thenReturn(Optional.of("report"));

        mockMvc.perform(get("/api/v1/ui-artifacts/ui-1/resources/answer")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value("report"));
    }
}
