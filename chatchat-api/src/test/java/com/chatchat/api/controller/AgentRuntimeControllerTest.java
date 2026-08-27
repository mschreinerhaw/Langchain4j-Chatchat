package com.chatchat.api.controller;

import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventType;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.run.AgentRunStep;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationService;
import com.chatchat.agents.runtime.evaluation.AgentProductionQualityService;
import com.chatchat.agents.runtime.evaluation.AgentProductionQualitySnapshot;
import com.chatchat.agents.runtime.trace.AgentRunTraceBuilder;
import com.chatchat.api.runtime.AgentRuntimeEventStreamService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentRuntimeControllerTest {

    @Test
    void listsRunsWithFilters() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRun run = run("runtime-run-1", AgentRunStatus.COMPLETED);
        when(runtime.list(any(AgentRunQuery.class))).thenReturn(List.of(run));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs")
                .param("status", "completed")
                .param("tenantId", "tenant-a")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].runId").value("runtime-run-1"))
            .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));

        verify(runtime).list(new AgentRunQuery(AgentRunStatus.COMPLETED, "tenant-a", null, null, 10, 0));
    }

    @Test
    void returnsTenantScopedProductionQualityAndRejectsTenantOverride() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentProductionQualityService qualityService = mock(AgentProductionQualityService.class);
        AgentRun tenantRun = run("quality-run", AgentRunStatus.COMPLETED, "tenant-a");
        AgentProductionQualitySnapshot snapshot = new AgentProductionQualitySnapshot(
            "agent_production_quality_v1", "tenant-a", 200L, 100L, 24,
            1, 1, 1, 1,
            Map.of("claimAuditPassRate", 1.0), Map.of("averageClaimCoverage", 1.0),
            Map.of("COMPLETED", 1L), Map.of("PASS", 1L), List.of(), List.of(), List.of());
        when(runtime.list(any(AgentRunQuery.class))).thenReturn(List.of(tenantRun));
        when(qualityService.summarize(List.of(tenantRun), "tenant-a", 24)).thenReturn(snapshot);
        MockMvc mockMvc = mockMvc(runtime, qualityService);

        mockMvc.perform(get("/api/v1/agent/runtime/quality")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .param("tenantId", "tenant-b")
                .param("windowHours", "24"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractVersion").value("agent_production_quality_v1"))
            .andExpect(jsonPath("$.data.tenantId").value("tenant-a"))
            .andExpect(jsonPath("$.data.rates.claimAuditPassRate").value(1.0));

        verify(runtime).list(new AgentRunQuery(null, "tenant-a", null, null, 1000, 0));
        verify(qualityService).summarize(List.of(tenantRun), "tenant-a", 24);
    }

    @Test
    void requiresTenantForProductionQualityWithoutAuthenticatedScope() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/quality"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("tenantId is required for production quality analytics"));
    }

    @Test
    void tenantContextOverridesListTenantFilter() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRun run = run("runtime-run-tenant", AgentRunStatus.COMPLETED, "tenant-a");
        when(runtime.list(any(AgentRunQuery.class))).thenReturn(List.of(run));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a")
                .param("tenantId", "tenant-b"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].runId").value("runtime-run-tenant"));

        verify(runtime).list(new AgentRunQuery(null, "tenant-a", null, null, 50, 0));
    }

    @Test
    void rejectsCrossTenantRunAccess() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.find("runtime-run-cross")).thenReturn(Optional.of(run(
            "runtime-run-cross",
            AgentRunStatus.RUNNING,
            "tenant-b"
        )));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs/runtime-run-cross")
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void returnsBadRequestForInvalidRunStatus() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs").param("status", "stuck"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("Unsupported agent run status: stuck"));
    }

    @Test
    void returnsUnavailableWhenRunStoreFails() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        doThrow(new IllegalStateException("store down")).when(runtime).list(any(AgentRunQuery.class));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs").param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(503))
            .andExpect(jsonPath("$.message").value("Agent runtime store unavailable"));
    }

    @Test
    void readsTimelineAndIncrementalRecords() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRun run = run("runtime-run-2", AgentRunStatus.RUNNING);
        AgentRunEvent event = AgentRunEvent.of("runtime-run-2", AgentRunEventType.STEP_RECORDED, "step", Map.of());
        AgentRunStep step = AgentRunStep.builder().step(2).action("tool").build();
        AgentObservation observation = AgentObservation.text("tool", "document_search", "observation");
        when(runtime.find("runtime-run-2")).thenReturn(Optional.of(run));
        when(runtime.events(eq("runtime-run-2"), eq(100L), eq(5))).thenReturn(List.of(event));
        when(runtime.steps(eq("runtime-run-2"), eq(1), eq(5))).thenReturn(List.of(step));
        when(runtime.observations(eq("runtime-run-2"), eq(2), eq(5))).thenReturn(List.of(observation));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs/runtime-run-2/timeline")
                .param("afterCreatedAt", "100")
                .param("eventLimit", "5")
                .param("afterStep", "1")
                .param("stepLimit", "5")
                .param("observationOffset", "2")
                .param("observationLimit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.run.runId").value("runtime-run-2"))
            .andExpect(jsonPath("$.data.events[0].type").value("STEP_RECORDED"))
            .andExpect(jsonPath("$.data.steps[0].step").value(2))
            .andExpect(jsonPath("$.data.observations[0].content").value("observation"));
    }

    @Test
    void readsOnlyEvidenceReferencedByAuthorizedRun() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        String runId = "runtime-run-evidence";
        String documentId = "agent-evidence-doc-1";
        AgentRun run = run(runId, AgentRunStatus.COMPLETED, "tenant-a");
        AgentObservation observation = AgentObservation.builder()
            .type("tool")
            .source("enterprise_metadata_search")
            .content("external evidence")
            .metadata(Map.of("stepOutputDocumentId", documentId, "stepOutputExternal", true))
            .build();
        when(runtime.find(runId)).thenReturn(Optional.of(run));
        when(runtime.observations(runId)).thenReturn(List.of(observation));
        when(runtime.evidence(documentId)).thenReturn(Optional.of(Map.of("rows", 1720)));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/runs/{runId}/evidence/{documentId}", runId, documentId)
                .requestAttr(ApiAuthenticationFilter.CURRENT_TENANT_ID, "tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.rows").value(1720));
    }

    @Test
    void cancelsRun() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.cancel("runtime-run-3")).thenReturn(run("runtime-run-3", AgentRunStatus.CANCELLED));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(post("/api/v1/agent/runtime/runs/runtime-run-3/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Agent run cancellation requested"))
            .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void readsSnapshot() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.snapshot()).thenReturn(AgentRuntimeSnapshot.empty());
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(get("/api/v1/agent/runtime/snapshot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalRuns").value(0));
    }

    @Test
    void opensEventStream() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentRuntimeEventStreamService streamService = mock(AgentRuntimeEventStreamService.class);
        when(streamService.streamEvents("runtime-run-4", 10L, 20, 500L, 1_000L)).thenReturn(new SseEmitter(1_000L));
        MockMvc mockMvc = standaloneSetup(new AgentRuntimeController(
            runtime,
            streamService,
            mock(AgentRunTraceBuilder.class),
            mock(AgentEvaluationService.class),
            mock(AgentProductionQualityService.class)
        )).build();

        mockMvc.perform(get("/api/v1/agent/runtime/runs/runtime-run-4/events/stream")
                .param("afterCreatedAt", "10")
                .param("limit", "20")
                .param("pollIntervalMs", "500")
                .param("timeoutMs", "1000"))
            .andExpect(status().isOk());

        verify(streamService).streamEvents("runtime-run-4", 10L, 20, 500L, 1_000L);
    }

    private AgentRun run(String runId, AgentRunStatus status) {
        return run(runId, status, null);
    }

    private AgentRun run(String runId, AgentRunStatus status, String tenantId) {
        return AgentRun.builder()
            .runId(runId)
            .request(AgentRunRequest.builder().tenantId(tenantId).build())
            .status(status)
            .events(List.of())
            .metadata(Map.of())
            .startedAt(System.currentTimeMillis())
            .build();
    }

    private MockMvc mockMvc(AgentRuntime runtime) {
        return mockMvc(runtime, mock(AgentProductionQualityService.class));
    }

    private MockMvc mockMvc(AgentRuntime runtime, AgentProductionQualityService qualityService) {
        return standaloneSetup(new AgentRuntimeController(
            runtime,
            mock(AgentRuntimeEventStreamService.class),
            mock(AgentRunTraceBuilder.class),
            mock(AgentEvaluationService.class),
            qualityService
        )).build();
    }
}
