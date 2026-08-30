package com.chatchat.agents.orchestration.analysis.insight;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.DataAnalysisSummaryProtocol;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolDefaults;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Schema-driven adapters from MCP result contracts to model reasoning evidence.
 *
 * <p>The registry is deliberately keyed by versioned output protocols rather than
 * tool names, target systems, table names, or business domains. Adapters preserve
 * returned facts and relationships while declaring the role and assessment boundary
 * of each collection.</p>
 */
public final class StructuredReasoningEvidenceAdapterRegistry {

    static final String PROJECTION_SCHEMA = "runtime_reasoning_evidence.v1";

    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> exact = new LinkedHashMap<>();
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> prefixes = new LinkedHashMap<>();
    private final DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryGovernanceBridge;

    public StructuredReasoningEvidenceAdapterRegistry() {
        this(RuntimeProtocolDefaults.analysisSummary());
    }

    public StructuredReasoningEvidenceAdapterRegistry(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryGovernanceBridge
    ) {
        this.summaryGovernanceBridge = summaryGovernanceBridge == null
            ? RuntimeProtocolDefaults.analysisSummary() : summaryGovernanceBridge;
        exact.put("structured_data_search_result.v1", this::structuredData);
        exact.put("api_requirement_analysis.v1", this::requirementCoverage);
        exact.put("http_requirement_analysis.v1", this::requirementCoverage);
        exact.put("metadata_ddl_annotation.v1", this::ddlAnnotation);
        prefixes.put("asset_query_result.", this::assetDiscovery);
    }

    public Map<String, Object> project(Object value) {
        Map<String, Object> root = findProtocolRoot(value, 0);
        String schema = evidenceSchema(root);
        if (schema == null) {
            return Map.of();
        }
        Function<Map<String, Object>, Map<String, Object>> adapter = exact.get(schema);
        if (adapter == null) {
            adapter = prefixes.entrySet().stream()
                .filter(entry -> schema.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        }
        return adapter == null ? Map.of() : adapter.apply(root);
    }

    public String format(Object value) {
        Map<String, Object> projection = project(value);
        return projection.isEmpty() ? null : ModelProtocolJson.compact(projection);
    }

    private Map<String, Object> structuredData(Map<String, Object> root) {
        List<Map<String, Object>> recordSets = maps(root.get("structuredData"));
        Map<String, Object> projection = base(root, "STRUCTURED_DATA_FACTS", "DATASET_RECORD_ANALYSIS");
        projection.put("factEvidence", recordSets);
        projection.put("analysisContexts", governedAnalysisContexts(recordSets));
        projection.put("referenceEvidence", maps(root.get("assets")));
        projection.put("claimCoverage", mapOf(
            "returnedDatasetCount", first(root, "structuredDatasetCount", recordSets.size()),
            "returnedObservationCount", first(root, "structuredObservationCount", countRows(recordSets)),
            "sourceDeclaredCoverageComplete", root.get("coverageComplete"),
            "warnings", list(root.get("warnings")),
            "skippedReason", root.get("skippedReason")
        ));
        projection.put("completeness", mapOf(
            "recordSetsReturned", recordSets.size(),
            "sourceCoverageComplete", root.get("coverageComplete"),
            "rule", "coverageComplete is source-declared retrieval coverage; it does not prove every possible dataset or time period was queried"
        ));
        projection.put("reasoningRules", List.of(
            "Rows inside factEvidence are returned observations and must remain associated with their dataset metadata.",
            "Use each dataset analysisContext as summary-governance input for identity, field semantics, analytical semantics, quality, analysis policy, source extensions, and explicit relationships; it is not observed data or a presentation-label mapping.",
            "Reference candidates are discovery metadata, not observed facts.",
            "Do not turn missing datasets, periods, or dimensions into factual conclusions."
        ));
        return immutable(projection);
    }

    private List<Map<String, Object>> governedAnalysisContexts(List<Map<String, Object>> recordSets) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (int index = 0; index < recordSets.size(); index++) {
            Map<String, Object> recordSet = recordSets.get(index);
            String reference = String.valueOf(recordSet.getOrDefault("dataset", "dataset-" + (index + 1)));
            contexts.add(summaryGovernanceBridge.govern(
                reference, map(recordSet.get("analysisContext")), maps(recordSet.get("rows"))));
        }
        return List.copyOf(contexts);
    }

    private Map<String, Object> requirementCoverage(Map<String, Object> root) {
        List<Map<String, Object>> groups = maps(root.get("coverage"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            candidates.add(mapOf(
                "requirement", group.get("requirement"),
                "candidateStatus", group.get("candidateStatus"),
                "returnedCount", group.get("returnedCount"),
                "templates", group.getOrDefault("templates", List.of()),
                "selectionProtocol", group.get("selectionProtocol")
            ));
        }
        Map<String, Object> projection = base(root, "GROUPED_EXECUTION_CANDIDATES", "REQUIREMENT_CANDIDATE_REVIEW");
        projection.put("candidateEvidence", List.copyOf(candidates));
        projection.put("claimCoverage", mapOf(
            "requirementCount", root.get("requirementCount"),
            "allRequirementsHaveCandidates", root.get("allRequirementsHaveCandidates"),
            "missingRequirementIds", list(root.get("missingRequirementIds"))
        ));
        projection.put("candidateCollectionContract", mapOf(
            "path", "$.coverage[*].templates",
            "groupKeyPath", "$.coverage[*].requirement.id",
            "candidateRole", "EXECUTION_TEMPLATE",
            "candidateIsAcceptance", false
        ));
        projection.put("reasoningRules", List.of(
            "CANDIDATES_FOUND proves retrieval only, never semantic acceptance or successful execution.",
            "Evaluate templates within their requirement group and preserve requirement-to-template association.",
            "Only template ids returned in candidateEvidence may be selected."
        ));
        return immutable(projection);
    }

    private Map<String, Object> ddlAnnotation(Map<String, Object> root) {
        List<Map<String, Object>> columns = maps(root.get("columns"));
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> standards = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            Object physical = column.get("physical");
            facts.add(mapOf(
                "evidenceRole", "SOURCE_PROVIDED_PHYSICAL_DEFINITION",
                "physical", physical
            ));
            standards.add(mapOf(
                "physical", physical,
                "standardFieldCandidate", column.get("standardField"),
                "standardTermCandidates", column.getOrDefault("standardTerms", List.of()),
                "standardDictionaryCandidates", column.getOrDefault("standardDictionaries", List.of()),
                "unmatchedNameTerms", column.getOrDefault("unmatchedNameTerms", List.of()),
                "matchConfidence", column.get("confidence"),
                "annotationStatus", column.get("annotationStatus")
            ));
        }
        Map<String, Object> projection = base(root, "SOURCE_FACTS_AND_STANDARD_REFERENCES", "FIELD_SEMANTIC_ANNOTATION_ONLY");
        projection.put("factEvidence", List.copyOf(facts));
        projection.put("standardEvidence", List.copyOf(standards));
        projection.put("evidenceCoverage", mapOf(
            "evidenceRole", "SOURCE_FACTS_AND_STANDARD_REFERENCES",
            "returnedEvidenceTypes", List.of(
                "source DDL physical column definitions",
                "enterprise field-standard candidate metadata"),
            "interpretation", "Describes returned evidence only; the model determines design conclusions"
        ));
        projection.put("reasoningRules", List.of(
            "Physical definitions came from supplied DDL and are not proof of deployed database state.",
            "ANNOTATED and matchConfidence describe candidate matching, not enterprise-design compliance.",
            "Standard references cannot substitute for missing physical facts."
        ));
        return immutable(projection);
    }

    private Map<String, Object> assetDiscovery(Map<String, Object> root) {
        Map<String, Object> projection = base(root, "ROUTING_CANDIDATES", "ASSET_ROUTING_ONLY");
        projection.put("candidateEvidence", root.getOrDefault("assets", List.of()));
        projection.put("unavailableCandidates", root.getOrDefault("unavailableAssets", List.of()));
        projection.put("selectionAudit", root.getOrDefault("assetSelectionAudit", Map.of()));
        projection.put("completeness", mapOf(
            "returnedCount", root.get("returnedCount"),
            "possiblyTruncated", root.get("possiblyTruncated"),
            "unavailableCount", root.get("unavailableCount")
        ));
        projection.put("reasoningRules", List.of(
            "Returned assets are authorized routing candidates, not observations about target health or business state.",
            "A non-empty candidate list proves discoverability only; semantic target selection remains separate.",
            "possiblyTruncated=true means the candidate set is partial."
        ));
        return immutable(projection);
    }

    private Map<String, Object> base(Map<String, Object> root, String role, String capability) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", PROJECTION_SCHEMA);
        projection.put("sourceSchemaVersion", evidenceSchema(root));
        projection.put("evidenceRole", role);
        projection.put("assessmentCapability", capability);
        projection.put("operation", mapOf(
            "success", root.get("success"),
            "status", root.get("status"),
            "error", first(root, "error", root.get("errorMessage"))
        ));
        return projection;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findProtocolRoot(Object value, int depth) {
        if (!(value instanceof Map<?, ?> raw) || depth > 6) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        String schema = evidenceSchema(map);
        if (schema != null && (exact.containsKey(schema)
            || prefixes.keySet().stream().anyMatch(schema::startsWith))) {
            return map;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload",
            "body", "output", "preview", "routingProjection")) {
            Map<String, Object> nested = findProtocolRoot(map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Map.of();
    }

    private int countRows(List<Map<String, Object>> recordSets) {
        return recordSets.stream().mapToInt(item -> number(item.get("count"), maps(item.get("rows")).size())).sum();
    }

    private String evidenceSchema(Map<String, Object> value) {
        String runtimeSchema = text(value.get("runtimeEvidenceSchemaVersion"));
        return runtimeSchema == null ? text(value.get("schemaVersion")) : runtimeSchema;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Object first(Map<String, Object> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item)).toList();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private Map<String, Object> immutable(Map<String, Object> value) {
        return Map.copyOf(value);
    }
}
