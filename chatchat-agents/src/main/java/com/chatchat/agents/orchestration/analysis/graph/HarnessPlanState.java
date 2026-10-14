package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;

/** Versioned public state using InterpretationPlan. Recording an outline never dispatches its steps. */
final class HarnessPlanState {
    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final Map<String,Object> metadata;
    private final AnalysisEvidenceSpillStore store;
    private final GovernanceIsolationScope scope;
    private final Consumer<Map<String,Object>> observe;
    private final String identity;
    private final Map<String,Object> initial;
    private final List<Map<String,Object>> versions = new ArrayList<>();
    private Map<String,Object> current = Map.of();

    HarnessPlanState(Map<String,Object> metadata, AnalysisEvidenceSpillStore store, GovernanceIsolationScope scope,
                     Consumer<Map<String,Object>> observe, String question) {
        this.metadata = metadata; this.store = store; this.scope = scope; this.observe = observe;
        identity = ModelProtocolJson.sha256Hex(Map.of("question", question, "scope", scope.toMap(),
            "submittedPlan", metadata.getOrDefault("analysisInterpretationPlan", Map.of())));
        // Acquisition plans govern the fixed system workflow; they are not model
        // analysis plans and must neither satisfy this gate nor be exposed here.
        initial = current;
        // Same tenant/user/run/question/accepted-plan identity; restoring state conveys no tool authority.
        store.readCheckpoint(scope, "harness:plan-state", identity).ifPresent(raw -> {
            try {
                Map<?,?> saved = JSON.readValue(raw, Map.class);
                if (saved.get("versions") instanceof List<?> history) for (Object item : history) {
                    if (!(item instanceof Map<?,?> version) || !(version.get("plan") instanceof Map<?,?> plan))
                        throw new IllegalArgumentException("Invalid plan checkpoint");
                    var parsed = parse(plan);
                    if (!new InterpretationPlanValidator().validateAnalysisPlan(parsed).valid())
                        throw new IllegalArgumentException("Invalid restored plan structure");
                    versions.add(stringMap(version));
                }
                if (!versions.isEmpty()) current = stringMap((Map<?,?>) versions.get(versions.size()-1).get("plan"));
            } catch (Exception invalid) { throw new IllegalStateException("PLAN_CHECKPOINT_INVALID", invalid); }
        });
        updateMetadata();
    }

    Map<String,Object> prepare(Map<String,Object> product) {
        if (product.get("workspace") instanceof Map<?,?> workspace && workspace.containsKey("plan"))
            throw new IllegalArgumentException("PLAN_FIELD_PATH: use response.plan (InterpretationPlan) and response.plan_change_reason; not workspace.plan");
        if (!product.containsKey("plan")) {
            if (current.isEmpty()) throw new IllegalArgumentException("PLAN_REQUIRED: submit a valid InterpretationPlan before evidence requests or final delivery");
            if (product.containsKey("plan_change_reason")) throw new IllegalArgumentException("PLAN_UPDATE_REQUIRED: a change reason must accompany a plan");
            return null;
        }
        if (!(product.get("plan") instanceof Map<?,?> raw)) throw new IllegalArgumentException("INVALID_PLAN: plan must be an InterpretationPlan object");
        InterpretationPlan plan = parse(raw);
        var validation = new InterpretationPlanValidator().validateAnalysisPlan(plan);
        if (!validation.valid()) throw new IllegalArgumentException("INVALID_PLAN: " + validation.errors());
        Map<String,Object> canonical = JSON.convertValue(plan, Map.class);
        String hash = ModelProtocolJson.sha256Hex(canonical);
        if (hash.equals(ModelProtocolJson.sha256Hex(current))) return null;
        String reason = product.get("plan_change_reason") instanceof String value ? value.trim() : "";
        if (!current.isEmpty() && reason.isBlank()) throw new IllegalArgumentException("PLAN_CHANGE_REASON_REQUIRED: explain the changed direction, scope or dependency publicly");
        return Map.of("revision", versions.size()+1, "plan", canonical, "changeReason", reason,
            "planSha256", hash, "author", "MODEL", "authority", "ANALYSIS_STATE_NOT_EXECUTION_AUTHORITY");
    }

    void commit(Map<String,Object> proposed) {
        if (proposed == null) return;
        var history = new ArrayList<>(versions); history.add(proposed);
        // Persist before observing/dispatching any request in the same response.
        try { store.checkpoint(scope, "harness:plan-state", identity, ModelProtocolJson.compact(Map.of("versions", history))); }
        catch (RuntimeException failed) { throw new IllegalStateException("PLAN_PERSISTENCE_FAILED: no request was executed", failed); }
        versions.add(proposed); current = stringMap((Map<?,?>) proposed.get("plan")); updateMetadata();
        observe.accept(Map.of("eventKind", "ANALYSIS_PLAN", "eventState", "RECORDED", "stage", "PLAN_RECORDED",
            "planVersion", versions.size(), "planSha256", proposed.get("planSha256"),
            "changeReason", proposed.get("changeReason"), "plan", current));
    }

    Map<String,Object> view() {
        if (current.isEmpty()) return Map.of("status", "PLAN_REQUIRED",
            "requiredResponseField", "plan",
            "instruction", "Submit your InterpretationPlan at response.plan before evidence exploration or final delivery; acquisition plans do not satisfy this contract.");
        // Do not repeat history or tool inputs; the original plan remains available by controlled read.
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", "ACCEPTED"); result.put("revision", versions.size());
        result.put("version", current.getOrDefault("version", ""));
        result.put("intent", current.getOrDefault("intent", Map.of()));
        result.put("context", current.getOrDefault("context", Map.of()));
        Object body = current.get("plan");
        Object rawSteps = body instanceof Map<?,?> plan ? plan.get("steps") : current.get("steps");
        if (rawSteps instanceof List<?> steps) result.put("steps", steps.stream().filter(Map.class::isInstance).map(item -> {
            var step = (Map<?,?>) item; var shape = new LinkedHashMap<String,Object>();
            for (String key : List.of("id", "action_type", "actionType", "tool_name", "toolName", "depends_on", "dependsOn"))
                if (step.containsKey(key)) shape.put(key, step.get(key));
            return shape;
        }).toList());
        result.put("planSha256", ModelProtocolJson.sha256Hex(current));
        if (!versions.isEmpty()) result.put("changeReason", versions.get(versions.size()-1).get("changeReason"));
        return result;
    }

    Map<String,String> capabilities() {
        return Map.of("READ_PLAN", "{operation,revision:current|1..latest,fromChar:0,maxChars:1..3000}; read this run's recorded public analysis plan JSON. Follow nextChar while hasMore; no execution or authority changes");
    }

    Map<String,Object> read(Map<String,Object> request) {
        Object raw = request.getOrDefault("revision", "current"); int revision;
        try { revision = "current".equals(raw) ? versions.size() : Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid plan revision"); }
        Map<String,Object> plan;
        if (revision == 0 && !initial.isEmpty()) plan = initial;
        else if (revision > 0 && revision <= versions.size()) plan = stringMap((Map<?,?>) versions.get(revision-1).get("plan"));
        else throw new IllegalArgumentException("Plan revision is not available in this run");
        String text = ModelProtocolJson.compact(plan);
        int from = integer(request.getOrDefault("fromChar", 0)), max = integer(request.getOrDefault("maxChars", 3000));
        if (from < 0 || from > text.length() || max < 1 || max > 3000) throw new IllegalArgumentException("Invalid plan text range");
        int end = Math.min(text.length(), from+max);
        return Map.of("operation", "READ_PLAN", "revision", revision, "text", text.substring(from,end),
            "totalChars", text.length(), "nextChar", end, "hasMore", end < text.length(), "planSha256", ModelProtocolJson.sha256Hex(plan));
    }

    private static int integer(Object raw) {
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("Integer range required");
        return number.intValue();
    }
    private static InterpretationPlan parse(Map<?,?> raw) {
        try { return JSON.readValue(ModelProtocolJson.compact(raw), InterpretationPlan.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("INVALID_PLAN: use InterpretationPlan fields; plan_change_reason belongs at response root"); }
    }
    private static Map<String,Object> stringMap(Map<?,?> raw) {
        var value = new LinkedHashMap<String,Object>(); raw.forEach((key,item) -> value.put(String.valueOf(key), item)); return value;
    }
    private void updateMetadata() {
        metadata.put("harnessPlanHistory", List.copyOf(versions)); metadata.put("harnessPlanState", view());
    }
}
