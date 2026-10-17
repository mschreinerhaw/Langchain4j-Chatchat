package com.chatchat.api.runtime.analysis;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.execution.RuntimeToolReceipt;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisSkillSelection;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Read-only, explicitly Skill-bound tool execution through the governed Tool Runtime. */
@Component
public class RegisteredToolAnalysisOperator implements AnalysisCapabilityOperator {
    public static final String TOOL_NAME = "runtime.analysis.toolName";
    public static final String TOOL_ARGUMENTS = "runtime.analysis.toolArguments";
    public static final String TOOL_CALLS = "runtime.analysis.toolCalls";
    public static final String CODING_DATA_OPERATION = "runtime.analysis.codingDataOperation";
    private static final int MAX_EVIDENCE_CHARS = com.chatchat.common.runtime.agent.AgentEvidenceBudget.ITEM_CHARS;

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
        return context != null && ((context.attributes().get(TOOL_NAME) instanceof String name && !name.isBlank())
            || context.attributes().get(TOOL_CALLS) instanceof List<?> calls && !calls.isEmpty());
    }

    @Override
    public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        if (context.attributes().get(TOOL_CALLS) instanceof List<?> calls) {
            if (calls.isEmpty() || calls.size() > 4) return denied("Select 1..4 governed MCP tools");
            List<com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence> evidence = new java.util.ArrayList<>();
            List<Map<String, Object>> receipts = new java.util.ArrayList<>();
            List<String> observations = new java.util.ArrayList<>();
            // Validate the batch before dispatching any member.
            if (calls.stream().anyMatch(raw -> !(raw instanceof Map<?, ?> call)
                || !(call.get("toolName") instanceof String name) || name.isBlank()
                || !(call.get("arguments") instanceof Map<?, ?>)))
                return denied("Each tool call requires a name and JSON object arguments");
            for (Object raw : calls) {
                if (!(raw instanceof Map<?, ?> call) || !(call.get("toolName") instanceof String name)
                    || name.isBlank() || !(call.get("arguments") instanceof Map<?, ?> arguments))
                    return denied("Each tool call requires a name and JSON object arguments");
                String skillId = call.get("skillId") instanceof String value && !value.isBlank()
                    ? value : context.skillId();
                if (context.attributes().get(AnalysisContext.SKILL_SELECTIONS_ATTRIBUTE) instanceof List<?> selections
                    && selections.stream().noneMatch(item -> item instanceof AnalysisSkillSelection selected
                        && selected.skillId().equals(skillId)))
                    {
                        receipts.add(Map.of("toolName", name, "status", "DENIED",
                            "errorMessage", "Tool Skill is not selected and authorized"));
                        observations.add("Tool Skill is not selected and authorized: " + name);
                        continue;
                    }
                AnalysisContext single = context.withAttribute(TOOL_NAME, name)
                    .withAttribute(TOOL_ARGUMENTS, arguments).withAttribute(TOOL_CALLS, null);
                if (!skillId.equals(context.skillId()))
                    single = new AnalysisContext(single.query(), single.kernelScope(), skillId,
                        List.of(), List.of(), single.roles(), single.intent(), single.attributes());
                WorkflowExecutionResult result = executeSingle(single, scope, plan);
                evidence.addAll(result.evidence());
                observations.addAll(result.observations());
                Map<String, Object> receipt = new LinkedHashMap<>(result.outputs());
                receipt.put("toolName", name);
                receipts.add(Map.copyOf(receipt));
            }
            return new WorkflowExecutionResult(evidence,
                Map.of("toolCallCount", calls.size(), "toolReceipts", receipts), observations);
        }
        return executeSingle(context, scope, plan);
    }

    private WorkflowExecutionResult executeSingle(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        if (scope == null || scope.tenantId() == null || scope.userId() == null)
            return denied("Authenticated tenant and user scope are required");
        String name = String.valueOf(context.attributes().get(TOOL_NAME));
        SkillDefinition skill = skills.list().stream().filter(item -> item.id().equals(context.skillId()))
            .findFirst().orElse(null);
        if (skill == null || !registry.hasTool(name) || !AnalysisToolBindingPolicy.bound(skill, name, catalog))
            return denied("Tool is not explicitly bound to the selected Skill");
        ToolMetadata metadata = registry.getToolMetadata(name);
        if (metadata == null || !metadata.isAgentCompatible() || !metadata.isUserVisible()
            || !"read".equalsIgnoreCase(metadata.getOperationType())
            || (metadata.getRuntimeLevel() != null && !Set.of("readonly", "read_only", "read", "discovery")
                .contains(metadata.getRuntimeLevel().toLowerCase(java.util.Locale.ROOT)))
            || Set.of("high", "forbidden").contains(String.valueOf(metadata.getRiskLevel()).toLowerCase()))
            return denied("Tool is not published as a read-only analysis capability");
        long invocationRevision = registry.getToolRevision(name);
        String contractHash = AnalysisToolContractIdentity.fingerprint(metadata, mapper);
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
        Map<String,Object> runtimeAttributes=new LinkedHashMap<>(Map.of("analysisSkillId",skill.id(),
            "toolRegistryRevisions",Map.of(name,invocationRevision)));
        if(context.attributes().get(CODING_DATA_OPERATION) instanceof String operation
                && operation.matches("data-[0-9a-f]{64}"))runtimeAttributes.put("toolRetryAttempts",0);
        try {
            execution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName(name).runtimeMode("analysis").requestId(requestId)
                .tenantId(scope.tenantId()).userId(scope.userId()).allowedTools(List.of(name))
                .toolInput(input).attributes(runtimeAttributes).build());
        } catch (RuntimeException failure) {
            if (failure instanceof java.util.concurrent.CancellationException
                || Thread.currentThread().isInterrupted()) throw failure;
            return new WorkflowExecutionResult(List.of(), Map.of("status", "FAILED",
                "exceptionType", failure.getClass().getSimpleName()),
                List.of(RuntimeToolReceipt.safeError(String.valueOf(failure.getMessage()))));
        }
        Map<String, Object> receipt = RuntimeToolReceipt.from(
            execution == null ? null : execution.output(), execution == null ? null : execution.outcome());
        if (execution == null || execution.output() == null || !execution.output().isSuccess())
            return new WorkflowExecutionResult(List.of(), receipt,
                List.of(String.valueOf(receipt.getOrDefault("errorMessage", receipt.get("status")))));
        if (registry.getToolRevision(name) != invocationRevision)
            return denied("Published tool contract changed during evidence acquisition");
        if (execution.output().getData() == null)
            return new WorkflowExecutionResult(List.of(), receipt, List.of("Tool succeeded with no data"));
        String content;
        try { content = mapper.writeValueAsString(execution.output().getData()); }
        catch (JsonProcessingException invalid) { return denied("Tool output cannot be represented as evidence"); }
        if (content == null || content.isBlank() || content.length() > MAX_EVIDENCE_CHARS)
            return denied("Tool output is empty or exceeds the evidence limit");
        var evidence = new ToolAnalysisEvidence(UUID.randomUUID().toString(), name, requestId, content,
            Map.of("skillId", skill.id(), "tenantId", scope.tenantId(),
                "toolRevision", invocationRevision, "toolContractHash", contractHash,
                "toolContractIdentityVersion", AnalysisToolContractIdentity.SCHEMA_VERSION,
                "authorizationParameters", authorizationScope(arguments),
                "runtimeOutcome", execution.outcome() == null ? "unknown" : execution.outcome()));
        Map<String, Object> facts = new LinkedHashMap<>(receipt);
        facts.put("evidenceReference", evidence.evidenceId());
        facts.put("executionScope", authorizationScope(arguments));
        return new WorkflowExecutionResult(List.of(evidence), facts, List.of());
    }

    private Map<String, Object> authorizationScope(Map<String, Object> arguments) {
        // Only the existing enterprise scope grammar is retained; invocation payloads and credentials are excluded.
        Map<String, Object> projection = new LinkedHashMap<>();
        for (String key : List.of("scopeExpression", "assetType", "capability", "action", "domain", "permissionLevel"))
            if (arguments.get(key) != null) projection.put(key, String.valueOf(arguments.get(key)));
        return Map.copyOf(projection);
    }

    private WorkflowExecutionResult denied(String reason) {
        return new WorkflowExecutionResult(List.of(), Map.of("status", "DENIED", "errorMessage", reason), List.of(reason));
    }
}
