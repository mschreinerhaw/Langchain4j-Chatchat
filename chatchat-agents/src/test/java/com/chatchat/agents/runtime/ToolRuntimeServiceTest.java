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
import static org.mockito.Mockito.never;
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

    @Test
    void requiresConfirmationBeforeMediumRiskMcpToolExecutes() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("mcp_customer_asset")).thenReturn(ToolMetadata.builder()
            .id("mcp_customer_asset")
            .title("Customer Asset")
            .riskLevel("medium")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("mcp_customer_asset")
            .runtimeMode("agent_chat")
            .requestId("req-4")
            .conversationId("conv-4")
            .tenantId("tenant-1")
            .userId("user-4")
            .allowedTools(List.of("mcp_customer_asset"))
            .toolInput(ToolInput.builder().userId("user-4").parameters(Map.of("customer_id", "c-001")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("confirmation_required");
        assertThat(execution.audit()).containsEntry("policyResult", "ask_before_execute");
        assertThat(execution.audit()).containsKey("confirmation");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void runtimeLevelForbiddenDeniesToolBeforeExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("server_restart")).thenReturn(ToolMetadata.builder()
            .id("server_restart")
            .title("Server Restart")
            .runtimeLevel("forbidden")
            .riskLevel("high")
            .operationType("write")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("server_restart")
            .runtimeMode("agent_chat")
            .requestId("req-forbidden")
            .conversationId("conv-forbidden")
            .tenantId("tenant-ops")
            .userId("ops-user")
            .allowedTools(List.of("server_restart"))
            .toolInput(ToolInput.builder().userId("ops-user").parameters(Map.of("service", "nginx")).build())
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("denied");
        assertThat(execution.audit()).containsEntry("runtimeLevel", "forbidden");
        assertThat(execution.audit().get("matchedPolicyRules").toString()).contains("runtime_level.forbidden=deny");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    void requestRuntimeLevelCanRequireConfirmationForReadonlyTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("log_search")).thenReturn(ToolMetadata.builder()
            .id("log_search")
            .title("Log Search")
            .riskLevel("low")
            .operationType("read")
            .build());
        ToolRuntimeService service = new ToolRuntimeService(toolRegistry, new ObjectMapper(), properties(), List.of(), List.of());

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("log_search")
            .runtimeMode("agent_chat")
            .requestId("req-confirm-level")
            .conversationId("conv-confirm-level")
            .tenantId("tenant-ops")
            .userId("ops-user")
            .allowedTools(List.of("log_search"))
            .toolInput(ToolInput.builder().userId("ops-user").parameters(Map.of("query", "error")).build())
            .attributes(Map.of("runtimeLevel", "confirm_required"))
            .build());

        assertThat(execution.output().isSuccess()).isFalse();
        assertThat(execution.outcome()).isEqualTo("confirmation_required");
        assertThat(execution.audit()).containsEntry("runtimeLevel", "confirm_required");
        assertThat(execution.audit()).containsKey("confirmation");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rememberedUserAllowOverridesAskToolAndParameterPolicies() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .riskLevel("low")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(
            ToolOutput.success("ok-1"),
            ToolOutput.success("ok-2")
        );

        McpPolicyProperties mcpPolicy = new McpPolicyProperties();
        mcpPolicy.setToolPolicy(Map.of("document_search", "ask_before_execute"));
        mcpPolicy.setParameterPolicy(Map.of(
            "document_search",
            Map.of("document_ids", "ask_before_execute")
        ));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            mcpPolicy,
            List.of(),
            List.of()
        );

        ToolRuntimeRequest firstRequest = documentSearchRequest(Map.of());
        ToolRuntimeExecution first = service.execute(firstRequest);
        Map<String, Object> confirmation = (Map<String, Object>) first.audit().get("confirmation");
        String token = String.valueOf(confirmation.get("token"));

        ToolRuntimeExecution confirmed = service.execute(documentSearchRequest(Map.of(
            "mcpConfirmation",
            Map.of(
                "token", token,
                "approved", true,
                "remember", "tool_auto_execute"
            )
        )));
        ToolRuntimeExecution remembered = service.execute(firstRequest);

        assertThat(first.outcome()).isEqualTo("confirmation_required");
        assertThat(confirmed.output().isSuccess()).isTrue();
        assertThat(remembered.output().isSuccess()).isTrue();
        assertThat(remembered.audit().get("matchedPolicyRules").toString()).contains("user_tool_policy=auto_execute");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void workflowDeniesSkippedRequiredStepAndAllowsAfterDependencyCompletes() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("query_customer_basic_info")).thenReturn(ToolMetadata.builder()
            .id("query_customer_basic_info")
            .title("Basic Info")
            .build());
        when(toolRegistry.getToolMetadata("query_customer_asset_summary")).thenReturn(ToolMetadata.builder()
            .id("query_customer_asset_summary")
            .title("Asset Summary")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        McpWorkflowProperties workflowProperties = new McpWorkflowProperties();
        McpWorkflowProperties.WorkflowSpec workflow = new McpWorkflowProperties.WorkflowSpec();
        McpWorkflowProperties.WorkflowStep first = new McpWorkflowProperties.WorkflowStep();
        first.setStep(1);
        first.setTool("query_customer_basic_info");
        first.setRequired(true);
        McpWorkflowProperties.WorkflowStep second = new McpWorkflowProperties.WorkflowStep();
        second.setStep(2);
        second.setTool("query_customer_asset_summary");
        second.setRequired(true);
        workflow.setSteps(List.of(first, second));
        workflowProperties.setWorkflows(Map.of("customer_asset_analysis", workflow));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            workflowProperties,
            List.of(),
            List.of()
        );

        ToolRuntimeRequest skipped = workflowRequest("query_customer_asset_summary", Map.of());
        ToolRuntimeExecution denied = service.execute(skipped);
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.output().getErrorMessage()).contains("required previous steps");
        verify(toolRegistry, never()).executeEnhancedTool(any(), any());

        ToolRuntimeExecution firstExecution = service.execute(workflowRequest("query_customer_basic_info", Map.of()));
        ToolRuntimeExecution secondExecution = service.execute(skipped);

        assertThat(firstExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.output().isSuccess()).isTrue();
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void agentWorkflowConfigDeniesSkippedStepWithoutGlobalWorkflow() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("query_customer_basic_info")).thenReturn(ToolMetadata.builder()
            .id("query_customer_basic_info")
            .title("Basic Info")
            .build());
        when(toolRegistry.getToolMetadata("query_customer_asset_summary")).thenReturn(ToolMetadata.builder()
            .id("query_customer_asset_summary")
            .title("Asset Summary")
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );

        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "ops_agent_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 4),
            "steps", List.of(
                Map.of("step", 1, "tool", "query_customer_basic_info", "required", true),
                Map.of("step", 2, "tool", "query_customer_asset_summary", "required", true)
            )
        );

        ToolRuntimeExecution denied = service.execute(agentWorkflowRequest(
            "query_customer_asset_summary",
            workflowConfig
        ));
        assertThat(denied.output().isSuccess()).isFalse();
        assertThat(denied.output().getErrorMessage()).contains("required previous steps");

        ToolRuntimeExecution firstExecution = service.execute(agentWorkflowRequest(
            "query_customer_basic_info",
            workflowConfig
        ));
        ToolRuntimeExecution secondExecution = service.execute(agentWorkflowRequest(
            "query_customer_asset_summary",
            workflowConfig
        ));

        assertThat(firstExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.output().isSuccess()).isTrue();
        assertThat(secondExecution.audit().get("matchedPolicyRules").toString()).contains("workflow.ops_agent_workflow.active");
        verify(toolRegistry, times(2)).executeEnhancedTool(any(), any());
    }

    @Test
    void agentWorkflowAutoExecuteOverridesParameterConfirmation() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .riskLevel("low")
            .operationType("read")
            .categories(List.of("mcp"))
            .build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        McpPolicyProperties mcpPolicy = new McpPolicyProperties();
        mcpPolicy.setToolPolicy(Map.of("document_search", "ask_before_execute"));
        mcpPolicy.setParameterPolicy(Map.of(
            "document_search",
            Map.of("document_ids", "ask_before_execute")
        ));
        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            mcpPolicy,
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );

        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "live_data_workflow",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 6),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "tool", "mcp_chatchat_mcp_server_document_search",
                    "required", true,
                    "confirmation", "auto_execute"
                )
            )
        );

        ToolRuntimeExecution execution = service.execute(ToolRuntimeRequest.builder()
            .toolName("document_search")
            .runtimeMode("agent_chat")
            .requestId("req-agent-workflow-auto")
            .conversationId("conv-agent-workflow-auto")
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder()
                .userId("user-agent-workflow")
                .parameters(Map.of("query", "Kafka Connect", "document_ids", List.of("doc-1")))
                .build())
            .attributes(Map.of("mcpWorkflow", workflowConfig))
            .build());

        assertThat(execution.output().isSuccess()).isTrue();
        assertThat(execution.audit().get("matchedPolicyRules").toString())
            .contains("tool_policy.document_search=ask_before_execute")
            .contains("workflow.live_data_workflow.document_search.confirmation=auto_execute");
        verify(toolRegistry, times(1)).executeEnhancedTool(any(), any());
    }

    @Test
    void hybridWorkflowAllowsParallelStageInAnyOrderBeforeNextStage() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().title("Tool").build());
        when(toolRegistry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success("ok"));

        ToolRuntimeService service = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            properties(),
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "hybrid_research",
            "executionStrategy", Map.of("mode", "hybrid", "stopOnError", true),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "name", "internal_retrieval",
                    "parallelSteps", List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search"),
                    "required", true
                ),
                Map.of("step", 2, "name", "external_verify", "tool", "mcp_xxx_web_search", "required", true)
            )
        );

        ToolRuntimeExecution deniedWeb = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        assertThat(deniedWeb.output().isSuccess()).isFalse();
        assertThat(deniedWeb.output().getErrorMessage())
            .contains("mcp_xxx_document_search")
            .contains("mcp_xxx_knowledge_search");

        ToolRuntimeExecution knowledgeFirst = service.execute(agentWorkflowRequest(
            "mcp_xxx_knowledge_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution webStillDenied = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution documentSecond = service.execute(agentWorkflowRequest(
            "mcp_xxx_document_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));
        ToolRuntimeExecution webAllowed = service.execute(agentWorkflowRequest(
            "mcp_xxx_web_search",
            workflowConfig,
            List.of("mcp_xxx_document_search", "mcp_xxx_knowledge_search", "mcp_xxx_web_search")
        ));

        assertThat(knowledgeFirst.output().isSuccess()).isTrue();
        assertThat(webStillDenied.output().isSuccess()).isFalse();
        assertThat(webStillDenied.output().getErrorMessage()).contains("mcp_xxx_document_search");
        assertThat(documentSecond.output().isSuccess()).isTrue();
        assertThat(webAllowed.output().isSuccess()).isTrue();
        verify(toolRegistry, times(3)).executeEnhancedTool(any(), any());
    }

    private ToolRuntimeProperties properties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setCircuitBreakerFailureThreshold(3);
        properties.setCircuitBreakerOpenSeconds(30);
        return properties;
    }

    private ToolRuntimeRequest workflowRequest(String toolName, Map<String, Object> parameters) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId("req-workflow")
            .conversationId("conv-workflow")
            .tenantId("tenant-1")
            .userId("user-workflow")
            .allowedTools(List.of("query_customer_basic_info", "query_customer_asset_summary"))
            .toolInput(ToolInput.builder().userId("user-workflow").parameters(parameters).build())
            .attributes(Map.of("executionPlan", Map.of("workflow", "customer_asset_analysis")))
            .build();
    }

    private ToolRuntimeRequest agentWorkflowRequest(String toolName, Map<String, Object> workflowConfig) {
        return agentWorkflowRequest(toolName, workflowConfig, List.of("query_customer_basic_info", "query_customer_asset_summary"));
    }

    private ToolRuntimeRequest agentWorkflowRequest(String toolName, Map<String, Object> workflowConfig, List<String> allowedTools) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId("req-agent-workflow")
            .conversationId("conv-agent-workflow")
            .tenantId("tenant-1")
            .userId("user-agent-workflow")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder().userId("user-agent-workflow").parameters(Map.of()).build())
            .attributes(Map.of("mcpWorkflow", workflowConfig))
            .build();
    }

    private ToolRuntimeRequest documentSearchRequest(Map<String, Object> attributes) {
        return ToolRuntimeRequest.builder()
            .toolName("document_search")
            .runtimeMode("agent_chat")
            .requestId("req-document-search")
            .conversationId("conv-document-search")
            .tenantId("tenant-1")
            .userId("user-document-search")
            .allowedTools(List.of("document_search"))
            .toolInput(ToolInput.builder()
                .userId("user-document-search")
                .parameters(Map.of("query", "PushGateway Prometheus", "document_ids", List.of("doc-1")))
                .build())
            .attributes(attributes)
            .build();
    }
}
