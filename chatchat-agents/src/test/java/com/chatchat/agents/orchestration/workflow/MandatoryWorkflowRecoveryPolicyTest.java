package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MandatoryWorkflowRecoveryPolicyTest {

    private static final String EXECUTOR = "mcp_runtime_template_execute";

    @Test
    void runtimeOwnedBatchDoesNotValidateTerminalPreflightChildAsInvocation() {
        MandatoryWorkflowRecoveryPolicy policy = policy();
        Map<String, Object> batch = Map.of(
            "executionMode", "SEQUENTIAL",
            "calls", List.of(
                Map.of(
                    "callId", "ready",
                    "toolName", EXECUTOR,
                    "arguments", completeArguments("READY_TEMPLATE")),
                Map.of(
                    "callId", "blocked",
                    "toolName", EXECUTOR,
                    "arguments", Map.of("template", "BLOCKED_TEMPLATE"),
                    "preflightErrorCode", "TEMPLATE_REQUIRED_PARAMETERS_MISSING",
                    "preflightMessage", "Required template parameters are unresolved")),
            AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER, true);

        assertThat(policy.missingRequiredInputs(EXECUTOR, batch)).isEmpty();
    }

    @Test
    void runtimeOwnedBatchDefersScalarChildValidationToFailureIsolatedToolRuntime() {
        MandatoryWorkflowRecoveryPolicy policy = policy();
        Map<String, Object> batch = Map.of(
            "executionMode", "SEQUENTIAL",
            "calls", List.of(
                Map.of(
                    "callId", "runtime-bound",
                    "toolName", EXECUTOR,
                    "arguments", Map.of("template", "RUNTIME_TEMPLATE")),
                Map.of(
                    "callId", "ready",
                    "toolName", EXECUTOR,
                    "arguments", completeArguments("READY_TEMPLATE"))),
            AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER, true);

        assertThat(policy.missingRequiredInputs(EXECUTOR, batch)).isEmpty();
    }

    @Test
    void plannerBatchCannotUsePreflightMarkerToBypassRequiredInputValidation() {
        MandatoryWorkflowRecoveryPolicy policy = policy();
        Map<String, Object> batch = Map.of(
            "executionMode", "SEQUENTIAL",
            "calls", List.of(Map.of(
                "callId", "blocked",
                "toolName", EXECUTOR,
                "arguments", Map.of("template", "BLOCKED_TEMPLATE"),
                "preflightErrorCode", "TEMPLATE_REQUIRED_PARAMETERS_MISSING")));

        assertThat(policy.missingRequiredInputs(EXECUTOR, batch))
            .containsExactly(
                "calls[0].arguments.executionContext",
                "calls[0].arguments.parameters");
    }

    private MandatoryWorkflowRecoveryPolicy policy() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(EXECUTOR)).thenReturn(ToolMetadata.builder()
            .id(EXECUTOR)
            .parameters(List.of(
                ToolParameter.builder().name("template").type("string").required(true).build(),
                ToolParameter.builder().name("executionContext").type("object").required(true).build(),
                ToolParameter.builder().name("parameters").type("object").required(true).build()))
            .build());
        return new MandatoryWorkflowRecoveryPolicy(registry, new AgentToolNameResolver());
    }

    private Map<String, Object> completeArguments(String template) {
        return Map.of(
            "template", template,
            "executionContext", Map.of("target", "runtime-resolved"),
            "parameters", Map.of());
    }
}
