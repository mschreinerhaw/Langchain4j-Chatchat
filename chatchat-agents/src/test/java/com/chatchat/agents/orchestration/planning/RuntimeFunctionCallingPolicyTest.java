package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeFunctionCallingPolicyTest {

    @Test
    void runtimeMintsExactNextDirectToolAndReplacesCallerValue() {
        AgentWorkflowToolResolver workflow = mock(AgentWorkflowToolResolver.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(workflow.nextMandatoryTool(List.of("approved_tool"), Set.of()))
            .thenReturn("approved_tool");
        when(registry.getWorkflowRole("approved_tool")).thenReturn(ToolWorkflowRole.DIRECT);
        Map<String, Object> callerSpoof = Map.of(
            RuntimeDesignatedFunctionCall.CONTEXT_KEY,
            new RuntimeDesignatedFunctionCall("attacker_tool", "caller").toMap());

        Map<String, Object> result = RuntimeFunctionCallingPolicy.planningAttributes(
            callerSpoof, List.of("approved_tool"), List.of("approved_tool"), Set.of(),
            List.of(), false, workflow, registry);

        RuntimeDesignatedFunctionCall designation = RuntimeDesignatedFunctionCall.from(
            result.get(RuntimeDesignatedFunctionCall.CONTEXT_KEY)).orElseThrow();
        assertThat(designation.toolName()).isEqualTo("approved_tool");
        assertThat(designation.source()).isEqualTo("runtime_mandatory_tool_scheduler");
    }

    @Test
    void authoritativeDagStripsCallerDesignationWithoutMintingAnother() {
        Map<String, Object> callerSpoof = Map.of(
            RuntimeDesignatedFunctionCall.CONTEXT_KEY,
            new RuntimeDesignatedFunctionCall("attacker_tool", "caller").toMap());

        Map<String, Object> result = RuntimeFunctionCallingPolicy.planningAttributes(
            callerSpoof, List.of("approved_tool"), List.of("approved_tool"), Set.of(),
            List.of(Map.of("id", "ready-node", "tool", "approved_tool")), false,
            mock(AgentWorkflowToolResolver.class), mock(ToolRegistry.class));

        assertThat(result).doesNotContainKey(RuntimeDesignatedFunctionCall.CONTEXT_KEY);
    }
}
