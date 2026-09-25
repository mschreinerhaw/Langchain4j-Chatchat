package com.chatchat.api.runtime;

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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class RegisteredToolAnalysisOperatorTest {
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
        assertThat(result.observations()).containsExactly("Governed tool execution failed or was denied");
    }

    @Test void governedRuntimeFailureNeverBecomesEvidence() {
        boundSkill();
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder()
            .operationType("read").runtimeLevel("readonly").build());
        doThrow(new IllegalStateException("transport unavailable")).when(runtime).execute(any());

        var result = operator.execute(context(), scope(), null);

        assertThat(result.evidence()).isEmpty();
        assertThat(result.observations()).containsExactly("Governed tool execution failed");
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
