package com.chatchat.e2e;

import com.chatchat.agents.runtime.AgentEvidenceStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.config.JsonRequestSizeFilter;
import com.chatchat.api.config.JsonRequestSizeProperties;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Production regression for the observed 20,054,016-character request failure. */
class ProductionOversizedAgentPayloadContainmentE2E {

    @Test
    void twentyMegabyteToolResultBecomesEvidenceReferenceAndCannotReenterHttpFeedbackBody() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("extreme_result_tool")).thenReturn(ToolMetadata.builder()
            .id("extreme_result_tool").title("Extreme Result Tool").build());
        when(registry.executeEnhancedTool(any(), any()))
            .thenReturn(ToolOutput.success(Map.of("payload", "x".repeat(20_054_016))));
        ToolRuntimeProperties runtimeProperties = new ToolRuntimeProperties();
        runtimeProperties.setMaxOutputBytes(262_144);
        runtimeProperties.setMaxOutputPreviewChars(16_000);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        AtomicInteger externalBytes = new AtomicInteger();
        runtime.setEvidenceStore(new AgentEvidenceStore() {
            @Override public boolean isEnabled() { return true; }
            @Override public void put(String documentId, String tenantId, String runId,
                                      String evidenceId, String json) { externalBytes.set(json.length()); }
            @Override public Optional<String> get(String documentId) { return Optional.empty(); }
            @Override public void delete(String documentId) { }
        });
        try {
            ToolRuntimeExecution execution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName("extreme_result_tool").runtimeMode("agent_chat").requestId("oversized-20m")
                .conversationId("conversation-20m").tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of("extreme_result_tool"))
                .toolInput(ToolInput.builder().parameters(Map.of()).build()).build());

            assertThat(externalBytes).hasValueGreaterThan(20_000_000);
            assertThat(new ObjectMapper().writeValueAsBytes(execution.output()).length).isLessThan(25_000);
            assertThat(execution.trace().getOutput()).hasSizeLessThan(5_000);

            JsonRequestSizeProperties httpProperties = new JsonRequestSizeProperties();
            JsonRequestSizeFilter filter = new JsonRequestSizeFilter(httpProperties, new ObjectMapper());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/tasks/t/feedback") {
                @Override public long getContentLengthLong() { return 20_054_016L; }
                @Override public int getContentLength() { return 20_054_016; }
            };
            request.setContentType("application/json");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean controllerInvoked = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> controllerInvoked.set(true));
            assertThat(controllerInvoked).isFalse();
            assertThat(response.getStatus()).isEqualTo(413);
        } finally {
            runtime.shutdown();
        }
    }
}
