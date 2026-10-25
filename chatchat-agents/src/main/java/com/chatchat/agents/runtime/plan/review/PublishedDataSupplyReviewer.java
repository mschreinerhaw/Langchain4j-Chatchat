package com.chatchat.agents.runtime.plan.review;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.template.TemplateWorkflowMetadata;
import com.chatchat.common.mcp.capability.McpDataSupplyPolicy;
import com.chatchat.common.tool.ToolMetadata;
import java.util.*;

/** Acquires publisher-declared baseline evidence; makes no semantic coverage claim. */
public final class PublishedDataSupplyReviewer {
    public static Optional<InterpretationPlanRuntime.StepReview> review(ToolMetadata tool,
            Map<String,Object> page,List<Map<String,Object>> candidates) {
        if (TemplateWorkflowMetadata.fixedScope(tool).isEmpty()
            || !McpDataSupplyPolicy.declared(TemplateWorkflowMetadata.publisher(tool))
            || page.containsKey(McpDataSupplyPolicy.METADATA_KEY) && !McpDataSupplyPolicy.declared(page)
            || !Boolean.TRUE.equals(page.get("success"))
            || !"FIXED_BINDING".equals(page.get("scopeMode"))
            || !Boolean.TRUE.equals(page.get("bindingComplete"))) return Optional.empty();
        Map<String,Map<String,Object>> authorized=new LinkedHashMap<>();
        for (Map<String,Object> candidate:candidates) {
            Object id=candidate.get("templateId");
            if (!(id instanceof String text) || text.isBlank()) return Optional.empty();
            if (authorized.putIfAbsent(text,candidate)!=null) return Optional.empty();
        }
        List<String> selected=authorized.entrySet().stream()
            .filter(entry->McpDataSupplyPolicy.parameterFree(entry.getValue()))
            .limit(ToolCallBatchSchema.DEFAULT_MAX_CALLS).map(Map.Entry::getKey).toList();
        if (selected.isEmpty()) return Optional.empty();
        List<String> deferred=authorized.keySet().stream().filter(id->!selected.contains(id)).toList();
        List<Map<String,Object>> protocols=selected.stream().map(id->Map.<String,Object>of(
            "protocol_version",AgentProtocolCatalog.TEMPLATE_PARAMETER,"template_id",id,
            "arguments",Map.of(),"unresolved_parameters",List.of(),
            "generation_mode","runtime_empty_schema")).toList();
        Map<String,Object> audit=new LinkedHashMap<>();
        audit.put("toolResultReviewMode","RUNTIME_PUBLISHED_DATA_SUPPLY");
        audit.put("publishedDataSupply",true);
        audit.put("selectedTemplateIds",selected);
        audit.put("deferredTemplateIds",deferred);
        audit.put("parameterProtocols",protocols);
        audit.put("parameterBindingMode","runtime_empty_schema");
        audit.put("coverageDecision","SCOPE_INSUFFICIENT");
        audit.put("analyticalCoverage","NOT_EVALUATED");
        audit.put("evidenceGaps",List.of("Baseline collection does not establish relevance or complete answer coverage."
            +(deferred.isEmpty()?"":" Parameterized or batch-limited templates remain deferred.")));
        audit.put(McpDataSupplyPolicy.METADATA_KEY,McpDataSupplyPolicy.fixedBaseline());
        return Optional.of(InterpretationPlanRuntime.StepReview.accepted(
            "Collect authorized parameter-free publisher baseline; final analysis owns relevance and sufficiency.",audit));
    }
}
