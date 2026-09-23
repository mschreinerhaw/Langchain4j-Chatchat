package com.chatchat.mcpserver.metadata.search.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataMatchingService;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataRequestAdapter;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes enterprise metadata discovery and field matching as one staged workflow. */
@Slf4j
@Component
public class EnterpriseMetadataSearchWorkflow extends AbstractStagedExecutionWorkflow<
    Map<String, Object>, EnterpriseMetadataSearchWorkflow.Analysis,
    EnterpriseMetadataSearchWorkflow.Plan, Map<String, Object>, Map<String, Object>> {

    public static final String WORKFLOW_ID = "search.enterprise-metadata.v1";
    private static final String TOOL_NAME = "enterprise_metadata_search";
    private static final int MAX_DISCOVERY_QUERY_CHARS = 512;
    private static final int MAX_DISCOVERY_TERMS = 120;
    private static final int MAX_DISCOVERY_TERM_CHARS = 128;

    private final EnterpriseMetadataMatchingService matchingService;
    private final EnterpriseMetadataSearchService searchService;
    private final EnterpriseMetadataRequestAdapter requestAdapter;

    public EnterpriseMetadataSearchWorkflow(EnterpriseMetadataMatchingService matchingService,
                                            EnterpriseMetadataSearchService searchService,
                                            EnterpriseMetadataRequestAdapter requestAdapter) {
        this.matchingService = matchingService;
        this.searchService = searchService;
        this.requestAdapter = requestAdapter;
    }

    @Override public String workflowId() { return WORKFLOW_ID; }

    @Override
    protected void validateInput(Map<String, Object> input, KernelDataScope scope) {
        if (input == null) throw new IllegalArgumentException("enterprise_metadata_search input is required");
    }

    @Override
    protected Analysis analyze(Map<String, Object> input, KernelDataScope scope) {
        Map<String, Object> normalized = normalizeSearchArguments(input);
        String mode = discoveryRequest(normalized) ? "DISCOVERY" : "FIELD_MATCH";
        return new Analysis(normalized, mode);
    }

    @Override
    protected Plan plan(Map<String, Object> input, Analysis analysis, KernelDataScope scope) {
        List<String> steps = "DISCOVERY".equals(analysis.mode())
            ? List.of("NORMALIZE_REQUIREMENTS", "CLASSIFY_SCENARIO", "RETRIEVE_REQUIRED_TYPES",
                "RANK_PER_REQUIREMENT", "VERIFY_CARDINALITY", "ASSEMBLE_EVIDENCE")
            : List.of("NORMALIZE_FIELDS", "RESOLVE_SCHEMA", "RETRIEVE_FIELD_CANDIDATES",
                "RANK_PER_FIELD", "VERIFY_COVERAGE", "ASSEMBLE_EVIDENCE");
        return new Plan(analysis.mode(), steps);
    }

    @Override
    protected Map<String, Object> executePlan(Map<String, Object> input, Analysis analysis,
                                              Plan plan, KernelDataScope scope) {
        return executeWorkflow(analysis.arguments());
    }

    @Override
    protected void verify(Map<String, Object> input, Analysis analysis, Plan plan,
                          Map<String, Object> execution, KernelDataScope scope) {
        if (execution == null) throw new IllegalStateException("enterprise_metadata_search produced no result");
        if (!execution.containsKey("success")) {
            throw new IllegalStateException("enterprise_metadata_search result has no success status");
        }
    }

    @Override
    protected Map<String, Object> assemble(Map<String, Object> input, Analysis analysis, Plan plan,
                                           Map<String, Object> execution, KernelDataScope scope) {
        Map<String, Object> result = new LinkedHashMap<>(execution);
        result.put("executionWorkflow", Map.of(
            "workflowId", WORKFLOW_ID,
            "mode", plan.mode(),
            "steps", plan.steps(),
            "verified", Boolean.TRUE.equals(execution.get("success"))
        ));
        return Collections.unmodifiableMap(result);
    }
    private Map<String, Object> executeWorkflow(Map<String, Object> arguments) {
        if (discoveryRequest(arguments)) {
            return executeDiscovery(arguments);
        }
        Map<String, Object> request = requestAdapter.adapt(arguments);
        List<Map<String, Object>> fields = maps(request.get("fields"));
        log.info("enterprise_metadata_search unified input requestId={} purpose={} fieldCount={} input={}",
            text(request.get("requestId")), text(request.get("purpose")),
            fields.size(), inputAudit(arguments, request, fields));
        if (fields.isEmpty()) {
            if (text(arguments.get("query")) != null) {
                return executeDiscovery(arguments);
            }
            Map<String, Object> missingEvidence = missingFieldEvidence(request);
            log.warn("enterprise_metadata_search unified request rejected requestId={} errorCode={} query={}",
                missingEvidence.get("requestId"), missingEvidence.get("errorCode"),
                text(request.get("query")));
            return missingEvidence;
        }
        Map<String, Object> result = new LinkedHashMap<>(matchingService.match(request));
        result.put("invokedCapability", TOOL_NAME);
        result.put("retrievalMode", "UNIFIED_FIELD_EVIDENCE_BUNDLE");
        log.info("enterprise_metadata_search unified output requestId={} resultSummary={}",
            result.get("requestId"), ToolLogSummarizer.summarizeResult(TOOL_NAME, result));
        return result;
    }

    private Map<String, Object> executeDiscovery(Map<String, Object> arguments) {
        List<String> inputTerms = strings(arguments.get("queryTerms"));
        if (inputTerms.isEmpty()) {
            inputTerms = List.of(text(arguments.get("query")));
        }
        Map<String, Object> result = new LinkedHashMap<>(searchService.searchRequirements(
            new EnterpriseMetadataSearchService.SearchRequest(
                text(arguments.get("query")),
                strings(firstPresent(arguments, "types", "metadataTypes")),
                strings(arguments.get("statuses")),
                strings(arguments.get("scenarios")),
                integerValue(firstPresent(arguments, "limit", "candidateLimit"))
            ),
            inputTerms
        ));
        result.put("invokedCapability", TOOL_NAME);
        result.put("operationMode", "ENTERPRISE_METADATA_DISCOVERY");
        result.put("inputTerms", inputTerms);
        log.info("enterprise_metadata_search discovery output requestId={} resultSummary={}",
            text(arguments.get("requestId")),
            ToolLogSummarizer.summarizeResult(TOOL_NAME, result));
        return Map.copyOf(result);
    }

    private Map<String, Object> normalizeSearchArguments(Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String rawQuery = text(normalized.get("query"));
        if (maps(normalized.get("fields")).isEmpty()) {
            validateDiscoveryQuery(rawQuery);
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTexts(terms, normalized.get("queryTerms"));
        addTexts(terms, normalized.get("searchTerms"));
        addTexts(terms, normalized.get("keywords"));
        addText(terms, normalized.get("keyword"));
        addTexts(terms, normalized.get("queries"));
        if (rawQuery != null && terms.size() > 1) {
            terms.remove(rawQuery);
        }
        if (terms.isEmpty() || (terms.size() == 1 && terms.contains(rawQuery))) {
            terms.clear();
            terms.addAll(discoveryTerms(rawQuery));
        }
        validateDiscoveryTerms(terms);
        if (!terms.isEmpty()) {
            normalized.put("query", rawQuery == null ? String.join(" ", terms) : rawQuery);
            normalized.put("queryTerms", List.copyOf(terms));
        }
        return normalized;
    }

    private List<String> discoveryTerms(String query) {
        if (query == null) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String item : query.split("[\\s,，、;；|]+")) {
            String term = text(item);
            if (term != null) {
                terms.add(term);
            }
            if (terms.size() >= MAX_DISCOVERY_TERMS) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    private void validateDiscoveryQuery(String query) {
        if (query != null && query.length() > MAX_DISCOVERY_QUERY_CHARS) {
            throw new IllegalArgumentException(
                "ENTERPRISE_METADATA_QUERY_CONTRACT_FAILED: query must contain retrieval concepts only; "
                    + "remove narrative/final-answer text and use queryTerms for multiple requirements");
        }
    }

    private void validateDiscoveryTerms(LinkedHashSet<String> terms) {
        if (terms.size() > MAX_DISCOVERY_TERMS) {
            throw new IllegalArgumentException(
                "ENTERPRISE_METADATA_QUERY_CONTRACT_FAILED: queryTerms exceeds " + MAX_DISCOVERY_TERMS);
        }
        for (String term : terms) {
            if (term.length() > MAX_DISCOVERY_TERM_CHARS) {
                throw new IllegalArgumentException(
                    "ENTERPRISE_METADATA_QUERY_CONTRACT_FAILED: each queryTerms item must be at most "
                        + MAX_DISCOVERY_TERM_CHARS + " characters and contain no narrative answer text");
            }
        }
    }

    private boolean discoveryRequest(Map<String, Object> arguments) {
        if (arguments == null || text(arguments.get("query")) == null
            || !maps(arguments.get("fields")).isEmpty()) {
            return false;
        }
        String purpose = text(arguments.get("purpose"));
        if (purpose != null && purpose.toUpperCase(Locale.ROOT).contains("ALIGNMENT")) {
            return false;
        }
        if (text(firstPresent(arguments, "tableName", "table")) != null) {
            return false;
        }
        Map<String, Object> target = stringMap(arguments.get("targetObject"));
        return text(firstPresent(target, "tableName", "name", "database")) == null;
    }

    private Map<String, Object> inputAudit(Map<String, Object> arguments,
                                           Map<String, Object> request,
                                           List<Map<String, Object>> fields) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("providedArgumentKeys", arguments == null ? List.of() : List.copyOf(arguments.keySet()));
        copy(input, request, "query");
        copy(input, request, "requestId");
        copy(input, request, "purpose");
        copy(input, request, "matchMode");
        copy(input, request, "targetObject");
        copy(input, request, "schemaEvidence");
        copy(input, request, "modelSearchProfile");
        copy(input, request, "matchStrategy");
        copy(input, request, "metadataTypes");
        copy(input, request, "statuses");
        copy(input, request, "scenarios");
        copy(input, request, "candidateLimit");
        input.put("fields", fields.stream().map(this::fieldInputAudit).toList());
        if (arguments != null) {
            copy(input, arguments, "tenantId");
            copy(input, arguments, "userId");
            copy(input, arguments, "defaultDataAsset");
            copy(input, arguments, "assetSelectionPolicy");
            copy(input, arguments, "mcpExecutionContext");
        }
        return Map.copyOf(input);
    }

    private Map<String, Object> fieldInputAudit(Map<String, Object> field) {
        Map<String, Object> input = new LinkedHashMap<>();
        copy(input, field, "fieldName");
        copy(input, field, "fieldCnName");
        copy(input, field, "description");
        copy(input, field, "dataType");
        copy(input, field, "nullable");
        copy(input, field, "domain");
        return Map.copyOf(input);
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> missingFieldEvidence(Map<String, Object> request) {
        String requestId = text(request.get("requestId"));
        return mapOf(
            "schemaVersion", EnterpriseMetadataMatchingService.SCHEMA_VERSION,
            "success", false,
            "requestId", requestId,
            "invokedCapability", TOOL_NAME,
            "retrievalMode", "UNIFIED_FIELD_EVIDENCE_BUNDLE",
            "errorCode", "ENTERPRISE_METADATA_INPUT_REQUIRED",
            "error", "Provide queryTerms/query for discovery, fields for matching, or an exact table identifier",
            "targetObject", request.get("targetObject"),
            "sourceSchema", mapOf(
                "mode", "UNRESOLVED",
                "fieldCount", 0,
                "fields", List.of(),
                "sourceEvidence", request.get("schemaEvidence")
            ),
            "fieldMatches", List.of(),
            "evidenceObjects", List.of(),
            "coverage", mapOf(
                "inputFieldCount", 0,
                "processedFieldCount", 0,
                "allFieldsProcessed", false,
                "requiredMetadataTypes", List.of(),
                "perFieldTypeRetrieval", true
            ),
            "reviewContract", mapOf(
                "reviewRequired", false,
                "decisionScope", "PER_FIELD",
                "factBoundary", "no_field_evidence_available",
                "instruction", "Provide queryTerms/query, a complete fields array, or an exact indexed table identifier."
            )
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(result);
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            String single = text(value);
            return single == null ? List.of() : List.of(single);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        iterable.forEach(item -> addText(result, item));
        return List.copyOf(result);
    }

    private void addTexts(java.util.Collection<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> addText(target, item));
        } else {
            addText(target, value);
        }
    }

    private void addText(java.util.Collection<String> target, Object value) {
        String candidate = text(value);
        if (candidate != null) {
            target.add(candidate);
        }
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
    public record Analysis(Map<String, Object> arguments, String mode) {
        public Analysis {
            arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    public record Plan(String mode, List<String> steps) {
        public Plan { steps = steps == null ? List.of() : List.copyOf(steps); }
    }
}
