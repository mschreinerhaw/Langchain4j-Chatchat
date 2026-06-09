package com.chatchat.agents.runtime;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRuntimeServiceTest {

    @Test
    void deniesToolOutsideAllowedPolicy() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("sql_query")).thenReturn(ToolMetadata.builder()
            .id("sql_query")
            .title("SQL Query")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("sql_query")
            .runtimeMode("agent_chat")
            .requestId("req-1")
            .conversationId("conv-1")
            .userId("user-1")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of("sql", "select 1")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.output().getErrorMessage()).contains("not allowed");
        assertThat(execution.trace().getRuntimeMetadata()).containsEntry("outcome", "denied");
        assertThat(service.snapshot().deniedCalls()).isEqualTo(1);
    }

    @Test
    void rateLimitRejectsSecondCallWithinWindow() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .isRateLimited(true)
            .maxCallsPerMinute(1)
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("web_search")
            .runtimeMode("tool_direct")
            .requestId("req-2")
            .conversationId("conv-2")
            .userId("user-2")
            .allowedTools(List.of("web_search"))
            .toolInput(ToolInput.builder().userId("user-2").parameters(Map.of("query", "weather")).build())
            .build();

        ToolRuntimeExecution first = service.execute(request);
        ToolRuntimeExecution second = service.execute(request);

        assertThat(first.output().isSuccess()).isTrue();
        assertThat(second.output().isSuccess()).isFalse();
        assertThat(second.trace().getRuntimeMetadata()).containsEntry("outcome", "rate_limited");
        assertThat(service.snapshot().rateLimitedCalls()).isEqualTo(1);
        verify(toolRegistry, times(1)).executeEnhancedTool(any(), any());
    }

    @Test
    void opensCircuitAfterRepeatedFailures() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_finance_quotes")).thenReturn(ToolMetadata.builder()
            .id("mcp_finance_quotes")
            .title("Finance Quotes")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.failure("boom-1"),
            ToolOutput.failure("boom-2")
        );
        ToolRuntimeProperties properties = properties();
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setCircuitBreakerOpenSeconds(60);
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties, List.of(), List.of());

        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("mcp_finance_quotes")
            .runtimeMode("agent_chat")
            .requestId("req-3")
            .conversationId("conv-3")
            .userId("user-3")
            .allowedTools(List.of("mcp_finance_quotes"))
            .toolInput(ToolInput.builder().userId("user-3").parameters(Map.of("query", "AAPL")).build())
            .build();

        ToolRuntimeExecution first = service.execute(request);
        ToolRuntimeExecution second = service.execute(request);
        ToolRuntimeExecution third = service.execute(request);

        assertThat(first.output().isSuccess()).isFalse();
        assertThat(second.output().isSuccess()).isFalse();
        assertThat(third.output().isSuccess()).isFalse();
        assertThat(third.trace().getRuntimeMetadata()).containsEntry("outcome", "circuit_open");
        assertThat(service.snapshot().openCircuits()).isEqualTo(1);
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    private ToolRuntimeProperties properties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setCircuitBreakerFailureThreshold(3);
        properties.setCircuitBreakerOpenSeconds(30);
        return properties;
    }
}
