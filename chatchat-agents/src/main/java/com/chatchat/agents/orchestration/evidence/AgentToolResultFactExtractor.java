package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/**
 * Produces safe, bounded facts from tool output for evidence review prompts.
 */
public final class AgentToolResultFactExtractor {

    private static final Set<String> EXECUTION_FACT_KEYS = Set.of(
        "localDecisionPhase",
        "localFactCheckSatisfied",
        "localFactCheckHasEvidence",
        "localFactCheckEvidenceType",
        "localFactCheckReason",
        "assetDiscoveryReturnedCount",
        "templateDiscoveryReturnedCount",
        "sqlMetadataFactChecked",
        "sqlMetadataColumnCount"
    );

    private final ObjectMapper objectMapper;

    public AgentToolResultFactExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Object redactExecutionStatements(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object child = entry.getValue();
                redacted.put(key, isExecutionStatementKey(key)
                    ? "[hidden: execution statement]"
                    : redactExecutionStatements(child));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redactExecutionStatements).toList();
        }
        return value;
    }

    public Map<String, Object> executionMetadata(InterpretationPlanRuntime.StepExecution execution) {
        if (execution == null || execution.metadata() == null || execution.metadata().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : execution.metadata().entrySet()) {
            if (EXECUTION_FACT_KEYS.contains(entry.getKey())) {
                facts.put(entry.getKey(), entry.getValue());
            }
        }
        return facts;
    }

    public Map<String, Object> structuredOutputFacts(Object output) {
        Map<String, Object> root = asMap(output);
        if (root.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> enterpriseRoot = enterpriseMetadataResultRoot(root, 0);
        if (!enterpriseRoot.isEmpty()) {
            root = enterpriseRoot;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        putIfPresent(facts, "schemaVersion", root.get("schemaVersion"));
        putIfPresent(facts, "category", root.get("category"));
        putIfPresent(facts, "dataSchemaVersion", root.get("dataSchemaVersion"));
        putIfPresent(facts, "success", root.get("success"));
        putIfPresent(facts, "status", root.get("status"));
        putIfPresent(facts, "errorMessage", root.get("errorMessage"));

        if (isEnterpriseMetadataResult(root)) {
            Map<String, Object> sourceSchema = asMap(root.get("sourceSchema"));
            Map<String, Object> coverage = asMap(root.get("coverage"));
            facts.put("sourceFieldCount", intValue(
                sourceSchema.get("fieldCount"), collectionSize(sourceSchema.get("fields"))));
            facts.put("returnedFieldMatchCount", collectionSize(root.get("fieldMatches")));
            facts.put("coverage", compactMap(coverage,
                "inputFieldCount", "processedFieldCount", "allFieldsProcessed",
                "requiredMetadataTypes", "perFieldTypeRetrieval"));
            facts.put("explicitTruncation", hasExplicitTruncationMarker(output, 0, new Counter()));
            facts.put("completenessSemantics",
                "allFieldsProcessed proves processing coverage only; per-field candidates prove metadata matches");
            return facts;
        }

        if (isSqlMetadataSearchResult(root)) {
            putIfPresent(facts, "totalMatched", root.get("totalMatched"));
            putIfPresent(facts, "catalogReturnedCount", root.get("catalogReturnedCount"));
            putIfPresent(facts, "returnedDetailCount",
                firstNonNull(root.get("detailReturnedCount"), root.get("returnedDetailCount")));
            putIfPresent(facts, "catalogTruncated", root.get("catalogTruncated"));
            putIfPresent(facts, "detailTruncated", root.get("detailTruncated"));
            facts.put("explicitTruncation", Boolean.TRUE.equals(root.get("catalogTruncated")));
            facts.put("truncationSemantics",
                "catalogTruncated controls physical table-name completeness; detailTruncated only controls column-detail completeness");
            return facts;
        }

        Map<String, Object> target = asMap(root.get("target"));
        if (!target.isEmpty()) {
            facts.put("target", compactMap(target,
                "type", "id", "name", "address", "ipAddress", "toolName", "environment"));
        }
        Map<String, Object> operation = asMap(root.get("operation"));
        if (!operation.isEmpty()) {
            facts.put("operation", compactMap(operation,
                "type", "template", "commandHash", "sourceTaskId", "reason"));
        }
        Map<String, Object> data = asMap(root.get("data"));
        if (!data.isEmpty()) {
            Map<String, Object> dataFacts = compactMap(data,
                "exitCode", "transportSuccess", "commandSuccess", "nonZeroStepIndexes",
                "failedStepIndex", "outputMode", "rowCount", "returnedRowCount", "complete",
                "possiblyTruncated", "truncationStrategy");
            Map<String, Object> outputLimits = asMap(data.get("outputLimits"));
            if (!outputLimits.isEmpty()) {
                dataFacts.put("outputLimits", outputLimits);
            }
            Integer stdoutLength = outputLength(data.get("stdout"));
            Integer stderrLength = outputLength(data.get("stderr"));
            if (stdoutLength != null) dataFacts.put("stdoutLength", stdoutLength);
            if (stderrLength != null) dataFacts.put("stderrLength", stderrLength);
            Map<String, Object> diagnostics = firstNonEmptyMap(
                data.get("diagnostics"), operation.get("diagnostics"));
            if (!diagnostics.isEmpty()) {
                dataFacts.put("diagnostics", compactMap(diagnostics,
                    "stdoutLength", "stderrLength", "stepCount", "exitCode", "transportSuccess",
                    "commandSuccess", "nonZeroStepIndexes", "durationMs"));
            }
            facts.put("data", dataFacts);
        }
        facts.put("explicitTruncation", hasExplicitTruncationMarker(output, 0, new Counter()));
        return facts.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> !(entry.getValue() instanceof Map<?, ?> map && map.isEmpty()))
            .collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                LinkedHashMap::putAll);
    }

    private boolean isExecutionStatementKey(String key) {
        if (key == null || key.isBlank()) return false;
        String normalized = key.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.equals("statement") || normalized.equals("sql")
            || normalized.equals("script") || normalized.equals("sqltemplate")
            || normalized.equals("renderedsql") || normalized.equals("querytext");
    }

    private boolean isSqlMetadataSearchResult(Map<String, Object> root) {
        String schemaVersion = stringValue(root == null ? null : root.get("schemaVersion"));
        return schemaVersion != null && schemaVersion.toLowerCase(Locale.ROOT)
            .contains("sql_metadata_search_result");
    }

    public boolean isEnterpriseMetadataResult(Map<String, Object> root) {
        return "enterprise_metadata_field_discovery.v1".equals(
            stringValue(root == null ? null : root.get("schemaVersion")));
    }

    public Map<String, Object> enterpriseMetadataResultRoot(Object value) {
        return enterpriseMetadataResultRoot(value, 0);
    }

    private Map<String, Object> enterpriseMetadataResultRoot(Object value, int depth) {
        if (depth > 5) return Map.of();
        Map<String, Object> candidate = asMap(value);
        if (candidate.isEmpty()) return Map.of();
        if (isEnterpriseMetadataResult(candidate)) return candidate;
        for (String key : List.of("data", "result", "payload", "structuredContent", "structured_content")) {
            Map<String, Object> nested = enterpriseMetadataResultRoot(candidate.get(key), depth + 1);
            if (!nested.isEmpty()) return nested;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (data instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private Map<String, Object> firstNonEmptyMap(Object first, Object second) {
        Map<String, Object> firstMap = asMap(first);
        return firstMap.isEmpty() ? asMap(second) : firstMap;
    }

    private Map<String, Object> compactMap(Map<String, Object> source, String... keys) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (source == null || source.isEmpty() || keys == null) return values;
        for (String key : keys) {
            if (key != null && source.containsKey(key) && source.get(key) != null) {
                values.put(key, source.get(key));
            }
        }
        return values;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null) target.put(key, value);
    }

    private Integer outputLength(Object value) {
        return value instanceof String text ? text.length() : null;
    }

    private boolean hasExplicitTruncationMarker(Object value, int depth, Counter counter) {
        if (value == null || depth > 8 || counter.value > 300) return false;
        counter.value++;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if (("_truncated".equals(key) || "possiblyTruncated".equals(key)
                    || key.endsWith("Truncated")) && Boolean.TRUE.equals(item)) return true;
                if (hasExplicitTruncationMarker(item, depth + 1, counter)) return true;
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (hasExplicitTruncationMarker(item, depth + 1, counter)) return true;
            }
            return false;
        }
        return value instanceof String text
            && (text.contains("...[truncated") || text.equals("[truncated]")
                || text.contains("...<truncated>"));
    }

    private static final class Counter {
        private int value;
    }
}
