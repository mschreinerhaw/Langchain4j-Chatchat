package com.chatchat.api.controller;

import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentRunQuery;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationService;
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
            mock(AgentEvaluationService.class)
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
        return standaloneSetup(new AgentRuntimeController(
            runtime,
            mock(AgentRuntimeEventStreamService.class),
            mock(AgentRunTraceBuilder.class),
            mock(AgentEvaluationService.class)
        )).build();
    }
}
