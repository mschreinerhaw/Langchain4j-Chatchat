package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.tool.CapabilityManifestInjector;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.orchestration.planning.validation.AgentPlanBudgetPolicy;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.BiFunction;

/** Read-only continuation through the existing authorized execution boundary, not another planner. */
public final class HarnessToolAccess {
    private final ToolRegistry registry;
    private final List<String> allowed;
    private final BiFunction<String, Map<String,Object>, ToolRuntimeExecution> execute;
    private final AnalysisEvidenceCoordinator evidence;
    private final Map<String,Object> attributes;
    private final Map<String,Object> metadata;
    private final GovernanceIsolationScope scope;
    private final AnalysisEvidenceSpillStore store;
    private final int maximumCalls;
    private int calls;

    public HarnessToolAccess(ToolRegistry registry, List<String> authorizedTools,
        BiFunction<String, Map<String,Object>, ToolRuntimeExecution> execute,
        AnalysisEvidenceCoordinator evidence, Map<String,Object> attributes, Map<String,Object> metadata,
        GovernanceIsolationScope scope, AnalysisEvidenceSpillStore store, int priorSteps) {
        this.registry = registry; this.execute = execute; this.evidence = evidence;
        this.attributes = attributes; this.metadata = metadata; this.scope = scope; this.store = store;
        this.allowed = authorizedTools.stream().filter(name -> {
            var tool = registry.getToolMetadata(name);
            return tool != null && tool.isAgentCompatible() && "read".equalsIgnoreCase(tool.getOperationType());
        }).distinct().toList();
        var caps = AgentPlanBudgetPolicy.fromRuntimeAttributes(attributes);
        this.maximumCalls = caps.maxSteps() == null ? 4 : Math.max(0, caps.maxSteps() - priorSteps);
    }

    public String capabilities() {
        if (maximumCalls == 0 || allowed.isEmpty()) return "No external continuation tools authorized.";
        var prompt = new StringBuilder("Optional CALL_TOOL request: {operation:'CALL_TOOL',toolName:'exact authorized name',arguments:{...}}. ");
        prompt.append("You may obtain additional evidence when useful; no call is required. Remaining call budget: ")
            .append(maximumCalls - calls).append(". Existing Runtime authorization, schema, timeout and confirmation gates apply.\n");
        for (String name : allowed) CapabilityManifestInjector.append(prompt, name, registry.getToolMetadata(name), new ObjectMapper());
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> call(Map<String,Object> request, Map<String, AnalysisEvidenceCoordinator.Dataset> sources) {
        String name = String.valueOf(request.get("toolName"));
        if (!allowed.contains(name)) throw new IllegalArgumentException("Tool is not authorized for read-only continuation");
        if (calls >= maximumCalls) throw new IllegalArgumentException("Agent tool-call budget exhausted");
        if (!(request.get("arguments") instanceof Map<?,?> values)) throw new IllegalArgumentException("Tool arguments must be an object");
        Map<String,Object> args = new LinkedHashMap<>(); values.forEach((key,value) -> args.put(String.valueOf(key),value));
        calls++;
        metadata.put("harnessToolCalls", calls);
        ToolRuntimeExecution execution = execute.apply(name, args);
        var output = execution.output();
        String reference = "harness:tool:" + calls;
        String fingerprint = ModelProtocolJson.sha256Hex(Map.of("tool", name, "arguments", args, "scope", scope.toMap()));
        store.checkpoint(scope, reference + ":output", fingerprint, ModelProtocolJson.compact(output));
        var traces = new ArrayList<Object>((List<Object>) metadata.getOrDefault("harnessToolTraces", List.of()));
        if (execution.trace() != null) traces.add(new ObjectMapper().convertValue(execution.trace(), Map.class));
        metadata.put("harnessToolTraces", List.copyOf(traces));
        if (!output.isSuccess()) return Map.of("status", "TOOL_FAILED", "toolName", name,
            "reason", String.valueOf(output.getErrorMessage()), "outcome", String.valueOf(execution.outcome()));
        var step = new InterpretationPlanRuntime.StepExecution(calls, "mcp_tool", name, true, output, null,
            execution, null, 0, Map.of());
        var projection = evidence.project(new InterpretationPlanRuntime.ExecutionResult("success", true, false,
            null, null, List.of(step), Map.of(), 0), attributes);
        List<Map<String,Object>> handles = new ArrayList<>();
        int index = 0;
        for (var dataset : projection.datasets()) {
            String ref = reference + ":" + (++index);
            sources.put(ref, new AnalysisEvidenceCoordinator.Dataset(ref, dataset.analysisContext(), dataset.handle()));
            handles.add(Map.of("datasetReference", ref, "recordCount", dataset.recordCount(),
                "sourceReference", dataset.reference(), "contentSha256", dataset.handle().contentSha256(),
                "preview", dataset.handle().readPage(0, 2).rows()));
        }
        metadata.put("harnessAvailableDatasetReferences", List.copyOf(sources.keySet()));
        var receipt = new LinkedHashMap<String,Object>(Map.of("status", "SUCCESS", "toolName", name, "datasets", handles,
            "rawResultReference", reference, "remainingCalls", maximumCalls - calls));
        if (ModelProtocolJson.compact(receipt).length() > 4000) {
            receipt.put("datasets", handles.stream().map(handle -> {
                Map<String,Object> compact = new LinkedHashMap<>(handle); compact.remove("preview"); return compact;
            }).toList());
            receipt.put("previewOmitted", true);
            receipt.put("readHint", "Use READ_RECORDS or CATALOG to access complete scoped results");
        }
        return receipt;
    }
}
