package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only, explicitly Skill-bound tool execution through the governed Tool Runtime. */
@Component
public class RegisteredToolAnalysisOperator implements AnalysisCapabilityOperator {
    public static final String TOOL_NAME = "runtime.analysis.toolName";
    public static final String TOOL_ARGUMENTS = "runtime.analysis.toolArguments";
    private static final int MAX_EVIDENCE_CHARS = 65536;

    private final SkillCatalogService skills;
    private final McpToolCatalogQueryPort catalog;
    private final ToolRegistry registry;
    private final ToolRuntimeService runtime;
    private final ObjectMapper mapper;

    public RegisteredToolAnalysisOperator(SkillCatalogService skills, McpToolCatalogQueryPort catalog,
                                          ToolRegistry registry, ToolRuntimeService runtime, ObjectMapper mapper) {
        this.skills = skills;
        this.catalog = catalog;
        this.registry = registry;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    @Override public AnalysisCapability capability() { return AnalysisCapability.TOOL_CALL; }

    @Override public boolean available(AnalysisContext context) {
        return context != null && context.attributes().get(TOOL_NAME) instanceof String name && !name.isBlank();
    }

    @Override
    public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        if (scope == null || scope.tenantId() == null || scope.userId() == null)
            return denied("Authenticated tenant and user scope are required");
        String name = String.valueOf(context.attributes().get(TOOL_NAME));
        SkillDefinition skill = skills.list().stream().filter(item -> item.id().equals(context.skillId()))
            .findFirst().orElse(null);
        if (skill == null || !registry.hasTool(name) || !explicitlyBound(skill, name))
            return denied("Tool is not explicitly bound to the selected Skill");
        ToolMetadata metadata = registry.getToolMetadata(name);
        if (metadata == null || !metadata.isAgentCompatible() || !metadata.isUserVisible()
            || !"read".equalsIgnoreCase(metadata.getOperationType())
            || (metadata.getRuntimeLevel() != null && !"readonly".equalsIgnoreCase(metadata.getRuntimeLevel()))
            || Set.of("high", "forbidden").contains(String.valueOf(metadata.getRiskLevel()).toLowerCase()))
            return denied("Tool is not published as a read-only analysis capability");
        Object rawArguments = context.attributes().get(TOOL_ARGUMENTS);
        if (rawArguments != null && !(rawArguments instanceof Map<?, ?>))
            return denied("Tool arguments must be an object");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (rawArguments instanceof Map<?, ?> values) {
            for (var item : values.entrySet()) {
                if (!(item.getKey() instanceof String key)) return denied("Tool argument names must be strings");
                arguments.put(key, item.getValue());
            }
        }
        try {
            if (mapper.writeValueAsString(arguments).length() > 8192)
                return denied("Tool arguments exceed the analysis limit");
        } catch (JsonProcessingException invalid) {
            return denied("Tool arguments are not valid JSON");
        }
        String requestId = context.kernelScope().requestId();
        ToolInput input = ToolInput.builder().requestId(requestId).userId(scope.userId())
            .parameters(arguments).build();
        ToolRuntimeExecution execution;
        try {
            execution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName(name).runtimeMode("analysis").requestId(requestId)
                .tenantId(scope.tenantId()).userId(scope.userId()).allowedTools(List.of(name))
                .toolInput(input).attributes(Map.of("analysisSkillId", skill.id(),
                    "toolRegistryRevisions", Map.of(name, registry.getToolRevision(name)))).build());
        } catch (RuntimeException failure) {
            return denied("Governed tool execution failed");
        }
        if (execution == null || execution.output() == null || !execution.output().isSuccess())
            return denied("Governed tool execution failed or was denied");
        if (execution.output().getData() == null)
            return denied("Governed tool produced no evidence data");
        String content;
        try { content = mapper.writeValueAsString(execution.output().getData()); }
        catch (JsonProcessingException invalid) { return denied("Tool output cannot be represented as evidence"); }
        if (content == null || content.isBlank() || content.length() > MAX_EVIDENCE_CHARS)
            return denied("Tool output is empty or exceeds the evidence limit");
        var evidence = new ToolAnalysisEvidence(UUID.randomUUID().toString(), name, requestId, content,
            Map.of("skillId", skill.id(), "tenantId", scope.tenantId(),
                "runtimeOutcome", execution.outcome() == null ? "unknown" : execution.outcome()));
        return new WorkflowExecutionResult(List.of(evidence), Map.of(), List.of());
    }

    private boolean explicitlyBound(SkillDefinition skill, String name) {
        if (skill.toolConfigs() != null && skill.toolConfigs().stream()
            .anyMatch(config -> config != null && name.equals(config.toolName())
                && Boolean.FALSE.equals(config.enabled())))
            return false;
        if (skill.boundMcpToolNames() != null && skill.boundMcpToolNames().contains(name)) return true;
        if (skill.toolConfigs() != null && skill.toolConfigs().stream()
            .anyMatch(config -> config != null && name.equals(config.toolName())
                && Boolean.TRUE.equals(config.enabled()))) return true;
        if (skill.boundMcpServiceIds() == null || skill.boundMcpServiceIds().isEmpty()) return false;
        return catalog.registeredTools().stream().anyMatch(tool -> name.equals(tool.localToolName())
            && skill.boundMcpServiceIds().contains(tool.serviceId()));
    }

    private WorkflowExecutionResult denied(String reason) {
        return new WorkflowExecutionResult(List.of(), Map.of(), List.of(reason));
    }
}
