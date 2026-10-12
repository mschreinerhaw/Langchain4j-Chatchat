package com.chatchat.agents.runtime.plan.contract;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlanEdgeContractValidatorTest {
    @Test void templateDiscoveryUsesRecordedExecutionScopeForEnvironmentContract() {
        var validator = new PlanEdgeContractValidator(new ToolProtocolPayloadNavigator());
        var semantics = mock(PlanEdgeContractValidator.RuntimeSemantics.class);
        when(semantics.isTemplateDiscoveryTool("bridge")).thenReturn(true);
        when(semantics.canonicalEnvironment("DEV")).thenReturn("DEV");
        var source = new InterpretationPlanRuntime.StepExecution(1, "mcp_tool", "bridge", true,
            Map.of("data", Map.of("schemaVersion", "template_query_result.v1")), null, null, null, 1L,
            Map.of("resolvedInput", Map.of("filters", Map.of("env", "DEV"))));
        var contract = new InterpretationPlan.EdgeContract(1, 2, "$.assets[0].asset.environment", "string", true);
        assertThat(validator.check(contract, source, null, semantics).success()).isTrue();
        when(semantics.isTemplateDiscoveryTool("bridge")).thenReturn(false);
        assertThat(validator.check(contract, source, null, semantics).success()).isFalse();
    }

    @Test void missingScopeCannotBeInventedForTemplateDiscovery() {
        var validator = new PlanEdgeContractValidator(new ToolProtocolPayloadNavigator());
        var semantics = mock(PlanEdgeContractValidator.RuntimeSemantics.class);
        when(semantics.isTemplateDiscoveryTool("bridge")).thenReturn(true);
        var source = new InterpretationPlanRuntime.StepExecution(1, "mcp_tool", "bridge", true,
            Map.of(), null, null, null, 1L, Map.of());
        var contract = new InterpretationPlan.EdgeContract(1, 2, "$.assets[0].asset.environment", "string", true);
        assertThat(validator.check(contract, source, null, semantics).success()).isFalse();
    }
}
