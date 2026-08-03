package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Release gate for the classic Agent path: discovery output externalization must
 * not lose the runtime-owned template or logical execution target.
 */
class ProductionTemplateExecutionContextContinuityE2E {

    @Test
    void oversizedApiSshAndDatabaseTemplateResultsRemainExecutableEndToEnd() throws Exception {
        for (Scenario scenario : List.of(
            new Scenario("api", "api_template_query", "api_template_execute", "templateId"),
            new Scenario("ssh", "ssh_template_query", "linux_command_execute", "template"),
            new Scenario("database", "database_query_template_query", "sql_query_execute", "template")
        )) {
            executeScenario(scenario);
        }
    }

    private void executeScenario(Scenario scenario) throws Exception {
        String namespace = "mcp_release_" + UUID.randomUUID().toString().replace("-", "") + "_";
        String discoveryTool = namespace + scenario.discoverySuffix();
        String executionTool = namespace + scenario.executionSuffix();
        String templateId = scenario.kind() + "_template_" + UUID.randomUUID();
        String assetName = scenario.kind() + "-asset-" + UUID.randomUUID();
        Map<String, Map<String, Object>> observedExecutions = new ConcurrentHashMap<>();

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0)).riskLevel("low").runtimeLevel("readonly")
            .operationType("read").confirmation(Map.of("default", "auto_execute"))
            .categories(List.of("mcp")).build());
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolInput input = invocation.getArgument(1);
            if (discoveryTool.equals(toolName)) {
                return ToolOutput.success(discoveryResult(scenario, templateId, assetName));
            }
            if (executionTool.equals(toolName)) {
                observedExecutions.put(input.getRequestId(), Map.copyOf(input.getParameters()));
                return ToolOutput.success(Map.of(
                    "schemaVersion", "template_execution_result.v1",
                    "templateId", templateId,
                    "success", true));
            }
            return ToolOutput.failure("unexpected tool");
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setMaxOutputBytes(8_192);
        properties.setMaxOutputPreviewChars(1_000);
        properties.setDefaultRetryAttempts(0);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), properties, List.of(), List.of());
        try {
            ToolRuntimeExecution discovery = runtime.execute(request(
                discoveryTool, "discover-" + scenario.kind(), List.of(discoveryTool), Map.of()));
            assertThat(discovery.output().isSuccess()).isTrue();
            assertThat(discovery.output().getData()).isInstanceOfSatisfying(Map.class, reference ->
                assertThat(reference).containsEntry("outputTruncated", true));
            assertThat(discovery.trace().getOutput())
                .contains("routingProjection", templateId)
                .doesNotContain("must-not-leak", "select secret", "cat /etc/shadow");

            AgentToolArgumentResolver resolver = new AgentToolArgumentResolver(
                new AgentToolNameResolver(), 5, registry);
            Map<String, Object> resolved = resolver.applyObservedTemplateContract(
                executionTool,
                Map.of("purpose", "release verification for " + scenario.kind()),
                List.of(discovery.trace()));

            assertThat(resolved)
                .containsEntry(scenario.templateArgument(), templateId)
                .containsEntry("parameters", Map.of())
                .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
            if (!"api".equals(scenario.kind())) {
                assertThat(resolved.get("executionContext"))
                    .isInstanceOfSatisfying(Map.class, context -> assertThat(context)
                        .containsEntry("assetName", assetName)
                        .containsEntry("env", "RELEASE"));
            }

            String executionRequestId = "execute-" + scenario.kind();
            ToolRuntimeExecution execution = runtime.execute(request(
                executionTool, executionRequestId, List.of(executionTool), resolved));
            assertThat(execution.output().isSuccess()).isTrue();
            assertThat(observedExecutions).containsKey(executionRequestId);
            assertThat(observedExecutions.get(executionRequestId))
                .containsEntry(scenario.templateArgument(), templateId);
        } finally {
            runtime.shutdown();
        }
    }

    private Map<String, Object> discoveryResult(Scenario scenario, String templateId, String assetName) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", templateId);
        template.put("description", "x".repeat(100_000));
        template.put("parameterSchema", Map.of(
            "type", "object", "properties", Map.of(), "required", List.of()));
        template.put("parameterContract", Map.of("executionTool", scenario.executionSuffix()));
        template.put("templateDsl", Map.of(
            "sql", "select secret", "command", "cat /etc/shadow", "password", "must-not-leak"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", scenario.kind() + "_template_query_result.v1");
        result.put("templates", List.of(template));
        if ("ssh".equals(scenario.kind())) {
            result.put("queryIr", Map.of("asset", Map.of("selected", Map.of(
                "id", UUID.randomUUID().toString(),
                "name", assetName,
                "environment", "RELEASE",
                "password", "must-not-leak"))));
        } else if ("database".equals(scenario.kind())) {
            template.put("sqlExecutionBinding", Map.of(
                "toolName", scenario.executionSuffix(),
                "templateId", templateId,
                "executionContext", Map.of("assetName", assetName, "env", "RELEASE")));
        }
        return result;
    }

    private ToolRuntimeRequest request(String toolName,
                                       String requestId,
                                       List<String> allowedTools,
                                       Map<String, Object> parameters) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("agent_chat")
            .requestId(requestId)
            .conversationId("release-conversation")
            .tenantId("release-tenant")
            .userId("release-user")
            .allowedTools(allowedTools)
            .toolInput(ToolInput.builder().requestId(requestId).parameters(parameters).build())
            .build();
    }

    private record Scenario(String kind,
                            String discoverySuffix,
                            String executionSuffix,
                            String templateArgument) {
    }
}
