package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.assessment.McpResultEvidencePolicy;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Task-scoped source dependency, never a modification of the maintained MCP DAG. */
public final class FileAnalysisDependencies {
    private FileAnalysisDependencies() {}
    public static ProblemAnalysisPlan.FileAnalysis files(Map<String,Object> attributes) {
        Object plan = attributes.get(ProblemAnalysisPlan.METADATA_KEY);
        if (plan instanceof ProblemAnalysisPlan accepted) return accepted.fileAnalysis();
        if (plan instanceof Map<?,?> restored) {
            try { return new ObjectMapper().convertValue(restored,ProblemAnalysisPlan.class).fileAnalysis(); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Stored file analysis plan is invalid",invalid); }
        }
        return null;
    }
    public static boolean optionalEmpty(Map<String,Object> attributes, InterpretationPlanRuntime.ExecutionResult result) {
        var files = files(attributes);
        if (files == null || files.toolDataDependency() != ProblemAnalysisPlan.ToolDataDependency.OPTIONAL
            || result == null || result.approvalRequired() || result.steps() == null) return false;
        var traces = new ArrayList<InteractionToolTrace>();
        try {
            for (var step : result.steps()) {
                if (step.toolName() == null || step.toolName().isBlank()) continue;
                Object observation = step.output();
                var execution = step.toolExecution();
                // A successful query can fail its maintained workflow's nonempty-result review.
                // Keep that failure intact; only its optional contribution to THIS file task is absent.
                if (execution != null) {
                    if (execution.output() == null || !execution.output().isSuccess()
                        || "confirmation_required".equalsIgnoreCase(execution.outcome())) return false;
                    observation = execution.output().getData();
                } else if (!step.success()) return false;
                var trace = InteractionToolTrace.builder().toolName(step.toolName()).success(true)
                    .output(new ObjectMapper().writeValueAsString(observation)).build();
                var availability = new McpResultEvidencePolicy().assess(List.of(trace)).availability();
                if (availability == McpResultEvidencePolicy.Availability.UNAVAILABLE) return false;
                // Discovery metadata (filters/counts/schema) is not returned business data.
                // Reuse its declared result schema; never infer emptiness from an assistant/tool name.
                if (emptyDiscovery(observation,0)) trace.setOutput("{\"rows\":[]}");
                traces.add(trace);
            }
        } catch (Exception invalid) { return false; }
        var assessment = new McpResultEvidencePolicy().assess(traces);
        return assessment.availability() == McpResultEvidencePolicy.Availability.EMPTY && !traces.isEmpty();
    }
    private static boolean emptyDiscovery(Object value,int depth) {
        if (depth > 8 || value == null) return false;
        Object canonical = com.chatchat.common.mcp.runtime.McpAnalysisPayload.canonicalData(value);
        if (canonical != value) return emptyDiscovery(canonical,depth+1);
        Object normalized = new com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator().normalize(value);
        if (normalized != value) return emptyDiscovery(normalized,depth+1);
        if (!(value instanceof Map<?,?> map) || Boolean.FALSE.equals(map.get("success"))) return false;
        String schema = Objects.toString(map.get("schemaVersion"),"");
        String key = schema.startsWith("asset_query_result.") ? "assets"
            : schema.startsWith("template_query_result.") ? "templates" : "";
        if (!key.isEmpty()) return map.get("returnedCount") instanceof Number count && count.longValue() == 0
            && map.get(key) instanceof Collection<?> items && items.isEmpty();
        for (String envelope : List.of("structuredContent","structured_content","data","result","payload","body","output","preview"))
            if (emptyDiscovery(map.get(envelope),depth+1)) return true;
        return false;
    }
}
