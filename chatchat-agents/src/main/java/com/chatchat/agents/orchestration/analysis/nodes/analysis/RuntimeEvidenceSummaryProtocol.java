package com.chatchat.agents.orchestration.analysis.nodes.analysis;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.tool.DataAnalysisContextProtocol;
import java.util.*;
/** Technical context/provenance adapter. It has no model execution or semantic admission policy. */
public final class RuntimeEvidenceSummaryProtocol implements DataAnalysisSummaryProtocol<AnalysisSummaryResult,GovernanceIsolationScope> {
    public Map<String, Object> govern(String reference,
                                      Map<String, Object> suppliedContext,
                                      List<Map<String, Object>> records) {
        Map<String, Object> supplied = copy(suppliedContext);
        Map<String, Object> validatedRoleContext = AgentRoleAnalysisContext.validate(
            supplied.get(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY));
        if (validatedRoleContext.isEmpty()) {
            supplied.remove(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
        } else {
            supplied.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, validatedRoleContext);
        }
        List<String> suppliedSections = new ArrayList<>();
        List<String> missingSemanticSections = new ArrayList<>();
        for (String section : List.of("source", "capability", "business", "schema", "relationships",
            "semantics", "quality", "analysisPolicy", "extensions")) {
            if (supplied.containsKey(section) && supplied.get(section) != null) suppliedSections.add(section);
            else missingSemanticSections.add(section);
        }

        Map<String, Object> source = copy(supplied.get("source"));
        source.putIfAbsent("runtimeReference", safeReference(reference));
        Map<String, Object> schema = copy(supplied.get("schema"));
        List<Map<String, Object>> derivedFields = returnedFields(records);
        if (!schema.containsKey("fields") || schema.get("fields") == null) {
            schema.put("fields", derivedFields);
        }

        Map<String, Object> governed = new LinkedHashMap<>(DataAnalysisContextProtocol.create(
            source,
            supplied.getOrDefault("capability", Map.of()),
            copy(supplied.get("business")),
            schema,
            supplied.getOrDefault("relationships", Map.of()),
            copy(supplied.get("semantics")),
            copy(supplied.get("quality")),
            copy(supplied.get("analysisPolicy")),
            copy(supplied.get("extensions"))
        ));
        supplied.forEach((key, value) -> {
            if (value != null && !List.of(
                "schemaVersion", "source", "capability", "business", "schema", "relationships",
                "semantics", "quality", "analysisPolicy", "extensions", "governance").contains(key)) {
                governed.put(key, value);
            }
        });
        Object suppliedSchemaVersion = supplied.get("schemaVersion");
        if (suppliedSchemaVersion != null
            && !DataAnalysisContextProtocol.SCHEMA_VERSION.equals(String.valueOf(suppliedSchemaVersion))) {
            governed.put("sourceContextSchemaVersion", String.valueOf(suppliedSchemaVersion));
        }
        governed.put("schemaVersion", DataAnalysisContextProtocol.SCHEMA_VERSION);
        governed.put("source", immutable(source));
        governed.put("schema", immutable(schema));
        Map<String, Object> governance = copy(governed.get("governance"));
        governance.putAll(copy(supplied.get("governance")));
        governance.put("bridgeSchemaVersion", BRIDGE_SCHEMA_VERSION);
        governed.put("governance", immutable(governance));
        governed.put("contextCompleteness", Map.of(
            "suppliedSections", List.copyOf(suppliedSections),
            "missingSemanticSections", List.copyOf(missingSemanticSections),
            "derivedFieldNamesOnly", !derivedFields.isEmpty()
                && !(copy(supplied.get("schema")).containsKey("fields")),
            "semanticInferenceAllowed", false
        ));
        return immutable(governed);
    }
    private List<Map<String, Object>> returnedFields(List<Map<String, Object>> records) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (records != null) {
            records.forEach(record -> {
                if (record != null) names.addAll(record.keySet());
            });
        }
        return names.stream().map(name -> Map.<String, Object>of("name", name)).toList();
    }
    private String safeReference(String reference) {
        return reference == null || reference.isBlank() ? "result" : reference;
    }
    private Map<String, Object> copy(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }
    private Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    public Map<String,Object> ledger(List<AnalysisSummaryResult> summaries,int returnedRecordCount,int processedRecordCount,boolean complete) {
        return Map.of("schemaVersion","evidence_processing.v1","returnedRecordCount",returnedRecordCount,
            "preparedRecordCount",processedRecordCount,"sourceAccessComplete",complete,"semanticQualityCertified",false,
            "resultIds",summaries.stream().map(AnalysisSummaryResult::resultId).toList());
    }
    public AnalysisSummaryResult finalResult(GovernanceIsolationScope scope,String stage,String content,String outcome,
        Map<String,Object> coverage,List<AnalysisSummaryResult> inputs) {
        return AnalysisSummaryResult.finalSummary(scope,stage,content,outcome,coverage,inputs);
    }
    public AnalysisSummaryResult finalResult(GovernanceIsolationScope scope,String stage,String content,String outcome,
        Map<String,Object> coverage,List<AnalysisSummaryResult> inputs,List<String> upstreamResultIds) {
        return AnalysisSummaryResult.finalSummary(scope,stage,content,outcome,coverage,inputs,upstreamResultIds);
    }
}
