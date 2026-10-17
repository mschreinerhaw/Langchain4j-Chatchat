package com.chatchat.api.runtime.analysis;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.model.SkillToolConfig;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisteredToolAnalysisOperatorTest {
    @Test void codingAcquisitionDisablesAutomaticRetryWithoutChangingOrdinaryAnalysisPolicy() {
        boundSkill();when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().operationType("read").runtimeLevel("readonly").build());
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(Map.of("value",42)),null,null,"SUCCESS",Map.of()));
        operator.execute(context().withAttribute(RegisteredToolAnalysisOperator.CODING_DATA_OPERATION,"data-"+"a".repeat(64)),scope(),null);
        operator.execute(context(),scope(),null);
        var calls=org.mockito.ArgumentCaptor.forClass(ToolRuntimeRequest.class);verify(runtime,org.mockito.Mockito.times(2)).execute(calls.capture());
        assertThat(calls.getAllValues().get(0).getAttributes()).containsEntry("toolRetryAttempts",0);
        assertThat(calls.getAllValues().get(1).getAttributes()).doesNotContainKey("toolRetryAttempts");
    }
    @Test void discoveryStageUsesDeclaredReadOperationAndStillRejectsRiskyTools() {
        boundSkill();
        var metadata = ToolMetadata.builder().operationType("read").runtimeLevel("discovery").riskLevel("low").build();
        when(registry.getToolMetadata("read_tool")).thenReturn(metadata);
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(ToolOutput.success(Map.of("assets", List.of())),
            null, null, "SUCCESS", Map.of()));
        assertThat(operator.execute(context(), scope(), null).evidence()).hasSize(1);
        metadata.setOperationType("write");
        assertThat(operator.execute(context(), scope(), null).evidence()).isEmpty();
        metadata.setOperationType("read"); metadata.setRiskLevel("high");
        assertThat(operator.execute(context(), scope(), null).evidence()).isEmpty();
        verify(runtime, org.mockito.Mockito.times(1)).execute(any());
    }
    private final SkillCatalogService skills = mock(SkillCatalogService.class);
    private final McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolRuntimeService runtime = mock(ToolRuntimeService.class);
    private final RegisteredToolAnalysisOperator operator = new RegisteredToolAnalysisOperator(
        skills, catalog, registry, runtime, new ObjectMapper());

    @Test void rejectsUnboundToolWithoutCallingRuntime() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skill.id()).thenReturn("finance");
        when(skills.list()).thenReturn(List.of(skill));
        when(registry.hasTool("read_tool")).thenReturn(true);

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.observations()).containsExactly("Tool is not explicitly bound to the selected Skill");
        verify(runtime, never()).execute(any());
    }

    @Test void rejectsWriteToolEvenWhenBound() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder()
            .operationType("write").build());

        assertThat(operator.execute(context(), scope(), null).evidence()).isEmpty();
        verify(runtime, never()).execute(any());
    }

    @Test void disabledToolConfigurationOverridesAnOlderBinding() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skill.id()).thenReturn("finance");
        when(skill.boundMcpToolNames()).thenReturn(List.of("read_tool"));
        when(skill.toolConfigs()).thenReturn(List.of(new SkillToolConfig("read_tool", null, null,
            null, List.of(), null, null, false)));
        when(skills.list()).thenReturn(List.of(skill));
        when(registry.hasTool("read_tool")).thenReturn(true);

        assertThat(operator.execute(context(), scope(), null).evidence()).isEmpty();
        verify(runtime, never()).execute(any());
    }

    @Test void producesEvidenceOnlyFromSuccessfulGovernedExecution() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder()
            .operationType("read").runtimeLevel("readonly").build());
        when(runtime.execute(any(ToolRuntimeRequest.class))).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("value", 42)), null, null, "SUCCESS", Map.of()));

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).content()).contains("42");
        assertThat(result.evidence().get(0).attributes()).containsEntry("tenantId", "tenant-1");
    }

    @Test void governedRuntimeDenialNeverBecomesEvidence() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder()
            .operationType("read").runtimeLevel("readonly").build());
        when(runtime.execute(any(ToolRuntimeRequest.class))).thenReturn(new ToolRuntimeExecution(
            ToolOutput.failure("permission denied"), null, null, "DENIED", Map.of()));

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.outputs()).containsEntry("status", "DENIED");
        assertThat(result.observations()).containsExactly("permission denied");
    }

    @Test void governedRuntimeFailureNeverBecomesEvidence() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder()
            .operationType("read").runtimeLevel("readonly").build());
        doThrow(new IllegalStateException("transport unavailable")).when(runtime).execute(any());

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.outputs()).containsEntry("status", "FAILED");
        assertThat(result.observations()).containsExactly("transport unavailable");
    }

    @Test void preservesSuccessfulEvidenceAndContinuesAfterOneBatchMemberIsDenied() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().operationType("read").build());
        when(runtime.execute(any())).thenReturn(
            new ToolRuntimeExecution(ToolOutput.success(Map.of("value", 1)), null, null, "SUCCESS", Map.of()),
            new ToolRuntimeExecution(ToolOutput.failure("scope denied"), null, null, "DENIED", Map.of()),
            new ToolRuntimeExecution(ToolOutput.success(Map.of("value", 3)), null, null, "SUCCESS", Map.of()));
        var calls = List.of(Map.of("toolName", "read_tool", "arguments", Map.of("sample", 1)),
            Map.of("toolName", "read_tool", "arguments", Map.of("sample", 2)),
            Map.of("toolName", "read_tool", "arguments", Map.of("sample", 3)));
        var result = operator.execute(context().withAttribute(RegisteredToolAnalysisOperator.TOOL_CALLS, calls), scope(), null);
        assertThat(result.evidence()).hasSize(2);
        assertThat((List<?>) result.outputs().get("toolReceipts")).hasSize(3);
        assertThat(result.observations()).containsExactly("scope denied");
        verify(runtime, org.mockito.Mockito.times(3)).execute(any());
    }

    private void boundSkill() {
        SkillDefinition skill = mock(SkillDefinition.class);
        when(skill.id()).thenReturn("finance");
        when(skill.boundMcpToolNames()).thenReturn(List.of("read_tool"));
        when(skills.list()).thenReturn(List.of(skill));
        when(registry.hasTool("read_tool")).thenReturn(true);
    }

    private AnalysisContext context() {
        return new AnalysisContext("Find value", new KernelDataScope("tenant-1", "user-1",
            "request-1", null, "run-1", null, Map.of()), "finance", List.of(), List.of(), List.of(),
            null, Map.of(RegisteredToolAnalysisOperator.TOOL_NAME, "read_tool",
                RegisteredToolAnalysisOperator.TOOL_ARGUMENTS, Map.of("year", 2025)));
    }

    private AnalysisScope scope() {
        return new AnalysisScope("tenant-1", "user-1", List.of(), List.of(), Map.of());
    }
}
