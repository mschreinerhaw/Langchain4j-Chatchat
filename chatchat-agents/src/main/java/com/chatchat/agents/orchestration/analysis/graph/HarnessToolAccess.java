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
    private static final String JOURNAL_VERSION = "harness_tool_execution.v1";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private BiFunction<String, Map<String,Object>, ToolRuntimeExecution> recoveryAdmission;
    private com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort executionCheckpoints;
    public HarnessToolAccess withExecutionCheckpoints(com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort port) {
        executionCheckpoints = port; return this;
    }
    private com.chatchat.common.kernel.KernelDataScope kernelScope() {
        return new com.chatchat.common.kernel.KernelDataScope(scope.tenantId(), scope.userId(), scope.requestId(),
            scope.conversationId(), scope.runId(), null, Map.of());
    }
    private final Map<String,String> inlineJournal = new HashMap<>();
    private final Map<String,ToolRuntimeExecution> pendingResults = new HashMap<>();
    private final Set<String> seenRequests = new HashSet<>();

    public HarnessToolAccess withRecoveryAdmission(BiFunction<String,Map<String,Object>,ToolRuntimeExecution> admission) {
        this.recoveryAdmission = admission;
        return this;
    }

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
        var prompt = new StringBuilder("Optional CALL_TOOL request: {operation:'CALL_TOOL',toolName:'exact authorized name',arguments:{...},requestId:'optional stable retry identity'}. ");
        prompt.append("In v2 repeat a receipt's requestId to recover that execution; omit it for a new exploration intent. Unknown outcomes are never automatically re-executed. ");
        prompt.append("You may obtain additional evidence when useful; no call is required. Remaining call budget: ")
            .append(maximumCalls - calls).append(". Existing Runtime authorization, schema, timeout and confirmation gates apply.\n");
        for (String name : allowed) CapabilityManifestInjector.append(prompt, name, registry.getToolMetadata(name), new ObjectMapper());
        return prompt.toString();
    }

    /** One model intent has one immutable scoped identity, independent of argument map ordering. */
    public synchronized Map<String,Object> call(Map<String,Object> request,
            Map<String,AnalysisEvidenceCoordinator.Dataset> sources, String runtimeIdentity) {
        String name = String.valueOf(request.get("toolName"));
        var currentTool = registry.getToolMetadata(name);
        if (!allowed.contains(name) || currentTool == null || !currentTool.isAgentCompatible()
            || !"read".equalsIgnoreCase(currentTool.getOperationType()))
            throw new IllegalArgumentException("Tool is not authorized for read-only continuation");
        if (!(request.get("arguments") instanceof Map<?,?> values)) throw new IllegalArgumentException("Tool arguments must be an object");
        Map<String,Object> args = new LinkedHashMap<>();
        for (var value : values.entrySet()) args.put(String.valueOf(value.getKey()), value.getValue());
        // Detach nested model objects; the execution layer may compile its own argument copy.
        args = decode(encode(args));
        String identity = request.get("requestId") instanceof String id ? id : runtimeIdentity;
        if (identity == null || identity.isBlank() || identity.length() > 256) throw new IllegalArgumentException("Invalid tool request identity");
        String key = "harness:call:" + ModelProtocolJson.sha256Hex(identity);
        String fingerprint = ModelProtocolJson.sha256Hex(encode(Map.of("tool", name, "arguments", args, "scope", scope.toMap())));
        if (executionCheckpoints == null && store.isEnabled() && !store.supportsAtomicCheckpoints())
            return recoveryReceipt(identity, name, "RECOVERY_UNAVAILABLE", "Atomic execution checkpoints are unavailable");
        metadata.put("harnessRecoveryMode", executionCheckpoints != null ? "SHARED_DATABASE"
            : store.isEnabled() ? "LOCAL_DURABLE_CHECKPOINT" : "INLINE_ONLY");
        String raw = readJournal(key);
        Map<String,Object> entry;
        boolean owner = false;
        if (raw == null) {
            int used = reserve(identity, fingerprint);
            entry = new LinkedHashMap<>(Map.of("schemaVersion", JOURNAL_VERSION, "state", "STARTED", "fingerprint", fingerprint,
                "requestId", identity, "toolRevision", registry.getToolRevision(name),
                "toolContractSha256", ModelProtocolJson.sha256Hex(encode(currentTool)), "remainingCalls", maximumCalls - used));
            raw = encode(entry);
            owner = compareJournal(key, null, raw);
            if (!owner) { raw = readJournal(key); entry = decode(raw); }
        } else { entry = decode(raw); reserve(identity, fingerprint); }
        if (!fingerprint.equals(entry.get("fingerprint"))) throw new IllegalArgumentException("Tool request identity conflicts with its original scope or arguments");
        if (seenRequests.add(identity)) calls++;
        if (!owner) {
            if (!ModelProtocolJson.sha256Hex(encode(currentTool)).equals(entry.get("toolContractSha256")))
                return recoveryReceipt(identity, name, "RECOVERY_ADMISSION_REJECTED", "Published tool contract changed");
            if (recoveryAdmission == null)
                return recoveryReceipt(identity, name, "RECOVERY_ADMISSION_UNAVAILABLE", "Current authorization must be rechecked before result recovery");
            Object originalInvocation = entry.getOrDefault("executedArguments", args);
            var pending = pendingResults.get(identity);
            if (pending != null && pending.trace() != null && pending.trace().getInput() != null)
                originalInvocation = pending.trace().getInput();
            var denied = recoveryAdmission.apply(name, decode(encode(originalInvocation)));
            if (denied != null) return recoveryReceipt(identity, name, "RECOVERY_ADMISSION_REJECTED", String.valueOf(denied.outcome()));
        }
        if ("RESULT_RECORDED".equals(entry.get("state"))) {
            pendingResults.remove(identity);
            var receipt = recoveredReceipt(identity, name, key, entry, sources);
            if (!"RESULT_AVAILABLE_PROJECTION_FAILED".equals(receipt.get("status")))
                metadata.put("harnessLastRecovery", Map.of("requestId", identity, "state", "RESTORED"));
            return receipt;
        }
        ToolRuntimeExecution execution = pendingResults.get(identity);
        if (execution == null && !owner)
            return recoveryReceipt(identity, name, "EXECUTION_OUTCOME_UNKNOWN", "Execution was claimed but no committed result exists; Runtime will not re-execute it");
        if (execution == null) {
            metadata.put("harnessActiveToolRequestId", identity);
            try { execution = execute.apply(name, decode(encode(args))); }
            catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException uncertain) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                return recoveryReceipt(identity, name, "EXECUTION_OUTCOME_UNKNOWN", "Invocation did not produce a committed execution receipt");
            } finally { metadata.remove("harnessActiveToolRequestId"); }
            if (execution == null || execution.output() == null)
                return recoveryReceipt(identity, name, "EXECUTION_OUTCOME_UNKNOWN", "Invocation returned no execution receipt");
            pendingResults.put(identity, execution);
        }
        var recorded = new LinkedHashMap<>(entry);
        recorded.put("state", "RESULT_RECORDED"); recorded.put("output", execution.output());
        recorded.put("executedArguments", execution.trace() != null && execution.trace().getInput() != null
            ? execution.trace().getInput() : args);
        recorded.put("trace", execution.trace()); recorded.put("outcome", execution.outcome()); recorded.put("audit", execution.audit());
        try {
            String resultJson = encode(recorded);
            if (!compareJournal(key, raw, resultJson)) {
                String committed = readJournal(key);
                if (!resultJson.equals(committed)) throw new IllegalStateException("Execution receipt commit conflict");
            }
            pendingResults.remove(identity);
            entry = decode(resultJson);
        } catch (RuntimeException persistenceFailure) {
            metadata.put("harnessLastRecovery", Map.of("requestId", identity, "state", "RESULT_PERSISTENCE_PENDING"));
            var receipt = new LinkedHashMap<>(recoveryReceipt(identity, name, "RESULT_PERSISTENCE_PENDING",
                "Retry this requestId to persist the retained result without another tool invocation"));
            receipt.put("remoteExecutionState", execution.output().isSuccess() ? "SUCCEEDED" : "FAILED");
            return receipt;
        }
        return recoveredReceipt(identity, name, key, entry, sources);
    }

    private int reserve(String identity, String fingerprint) {
        String key = "harness:call-budget";
        String owner = ModelProtocolJson.sha256Hex(encode(scope.toMap()));
        for (int attempt = 0; attempt < 64; attempt++) {
            String raw = readJournal(key);
            var budget = raw == null ? new LinkedHashMap<String,Object>(Map.of("owner", owner, "requests", Map.of(), "limit", maximumCalls)) : decode(raw);
            if (!owner.equals(budget.get("owner"))) throw new SecurityException("Execution budget belongs to another request principal");
            var requests = new LinkedHashMap<String,Object>(decode(encode(budget.get("requests"))));
            if (requests.containsKey(identity)) {
                if (!fingerprint.equals(requests.get(identity))) throw new IllegalArgumentException("Tool request identity conflicts with its original scope or arguments");
                metadata.put("harnessToolCalls", requests.size()); return requests.size();
            }
            if (requests.size() >= Math.min(maximumCalls, ((Number)budget.get("limit")).intValue()))
                throw new IllegalArgumentException("Agent tool-call budget exhausted");
            requests.put(identity, fingerprint); budget.put("requests", requests);
            if (compareJournal(key, raw, encode(budget))) { metadata.put("harnessToolCalls", requests.size()); return requests.size(); }
        }
        throw new IllegalStateException("Execution budget reservation contention");
    }

    private String readJournal(String key) {
        if (executionCheckpoints != null) return executionCheckpoints.readExecutionCheckpoint(kernelScope(), key).orElse(null);
        return store.isEnabled() ? store.readCheckpoint(scope, key, JOURNAL_VERSION).orElse(null) : inlineJournal.get(key);
    }
    private boolean compareJournal(String key, String expected, String next) {
        if (executionCheckpoints != null) return executionCheckpoints.compareAndSetExecutionCheckpoint(kernelScope(), key, expected, next);
        if (store.isEnabled()) return store.compareAndSetCheckpoint(scope, key, JOURNAL_VERSION, expected, next);
        if (!Objects.equals(inlineJournal.get(key), expected)) return false;
        inlineJournal.put(key, next); return true;
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Execution receipt serialization failed", failure); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> decode(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Execution receipt integrity failed", failure); }
    }
    private Map<String,Object> recoveryReceipt(String identity, String name, String status, String reason) {
        metadata.put("harnessLastRecovery", Map.of("requestId", identity, "state", status));
        return Map.of("requestId", identity, "toolName", name, "status", status, "reason", reason);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> recoveredReceipt(String identity, String name, String key,
            Map<String,Object> entry, Map<String,AnalysisEvidenceCoordinator.Dataset> sources) {
        var output = JSON.convertValue(entry.get("output"), com.chatchat.common.tool.ToolOutput.class);
        var execution = new ToolRuntimeExecution(output, registry.getToolMetadata(name),
            entry.get("trace") == null ? null : JSON.convertValue(entry.get("trace"), com.chatchat.common.interaction.InteractionToolTrace.class),
            String.valueOf(entry.get("outcome")), entry.get("audit") instanceof Map<?,?> audit ? (Map<String,Object>)audit : Map.of());
        Map<String,Object> projected;
        try { projected = projectExecution(name, "harness:tool:" + key.substring("harness:call:".length()), execution, sources); }
        catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException projectionFailure) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            return recoveryReceipt(identity, name, "RESULT_AVAILABLE_PROJECTION_FAILED",
                "Execution result is committed; retry this requestId to rebuild evidence without invoking the tool");
        }
        var receipt = new LinkedHashMap<>(projected);
        receipt.put("requestId", identity); receipt.put("remainingCalls", entry.get("remainingCalls"));
        metadata.put("harnessLastRecovery", Map.of("requestId", identity, "state", "RESULT_RECORDED"));
        return receipt;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> call(Map<String,Object> request, Map<String, AnalysisEvidenceCoordinator.Dataset> sources) {
        String name = String.valueOf(request.get("toolName"));
        if (!allowed.contains(name)) throw new IllegalArgumentException("Tool is not authorized for read-only continuation");
        if (calls >= maximumCalls) throw new IllegalArgumentException("Agent tool-call budget exhausted");
        if (!(request.get("arguments") instanceof Map<?,?> values)) throw new IllegalArgumentException("Tool arguments must be an object");
        Map<String,Object> args = new LinkedHashMap<>();
        for (var value : values.entrySet()) args.put(String.valueOf(value.getKey()), value.getValue());
        calls++;
        metadata.put("harnessToolCalls", calls);
        ToolRuntimeExecution execution = execute.apply(name, args);
        var output = execution.output();
        String reference = "harness:tool:" + calls;
        String fingerprint = ModelProtocolJson.sha256Hex(Map.of("tool", name, "arguments", args, "scope", scope.toMap()));
        store.checkpoint(scope, reference + ":output", fingerprint, ModelProtocolJson.compact(output));
        return projectExecution(name, reference, execution, sources);
    }

    @SuppressWarnings("unchecked") private Map<String,Object> projectExecution(String name, String reference,
            ToolRuntimeExecution execution, Map<String,AnalysisEvidenceCoordinator.Dataset> sources) {
        var output = execution.output();
        var traces = new ArrayList<Object>((List<Object>) metadata.getOrDefault("harnessToolTraces", List.of()));
        if (execution.trace() != null) {
            var trace = JSON.convertValue(execution.trace(), Map.class);
            if (!traces.contains(trace)) traces.add(trace);
        }
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
