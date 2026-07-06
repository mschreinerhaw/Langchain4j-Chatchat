package com.chatchat.mcpserver.routing;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TargetKindRegistry {

    public static final String FILTERS_SCHEMA_VERSION = "target_filters.v1";
    public static final double MIN_CONFIDENCE = 0.60;
    public static final String DECISION_ACCEPTED = "ACCEPTED";
    public static final String DECISION_REVIEW_REQUIRED = "REVIEW_REQUIRED";
    private static final List<String> TARGET_KIND_FIELDS = List.of(
        "targetKind",
        "target_kind",
        "queryDomain",
        "query_domain",
        "domain",
        "resourceType",
        "resource_type",
        "resourceKind",
        "resource_kind"
    );
    private static final List<String> FILTER_SCHEMA_FIELDS = List.of(
        "filtersSchemaVersion",
        "filters_schema_version"
    );
    private static final List<String> TRACE_FIELDS = List.of(
        "trace",
        "routingTrace",
        "routing_trace"
    );
    private static final List<String> FINAL_DECISION_FIELDS = List.of(
        "finalDecision",
        "final_decision",
        "selectedTargetKind",
        "selected_target_kind"
    );
    private static final List<String> CANDIDATE_FIELDS = List.of(
        "candidates",
        "routingCandidates",
        "routing_candidates",
        "targetKindCandidates",
        "target_kind_candidates"
    );

    private final Map<String, TargetKindDefinition> byKind;
    private final Map<String, TargetKindDefinition> byAssetType;

    public TargetKindRegistry() {
        this.byKind = definitionsByKind();
        this.byAssetType = definitionsByAssetType(byKind);
    }

    public Resolution resolveForTool(String toolName,
                                     Object requestedAssetType,
                                     Map<String, Object> arguments,
                                     Map<String, Object> filters) {
        String normalizedTool = normalizeToolName(toolName);
        String assetType = normalizeAssetType(text(requestedAssetType));
        List<RoutingCandidate> candidates = routingCandidates(normalizedTool, arguments, filters);
        String targetKindRaw = finalDecision(arguments, filters, candidates);
        if (targetKindRaw == null) {
            throw error(
                "TARGET_KIND_REQUIRED",
                normalizedTool + " requires finalDecision or explicit targetKind.",
                "finalDecision",
                List.of("finalDecision", "candidates"),
                allowedTargetKindsForTool(normalizedTool),
                null
            );
        }
        String targetKind = normalizeTargetKind(targetKindRaw);
        if (!hasFilterEnvelope(arguments)) {
            throw error(
                "FILTERS_REQUIRED",
                normalizedTool + " requires explicit filters object, even when it is empty.",
                "filters",
                List.of("filters"),
                List.of("object"),
                null
            );
        }
        TargetKindDefinition earlyDefinition = byKind.get(targetKind);
        if (earlyDefinition != null
            && !candidates.isEmpty()
            && candidates.stream().noneMatch(candidate -> targetKind.equals(candidate.targetKind()))) {
            throw error(
                "FINAL_DECISION_NOT_IN_CANDIDATES",
                "finalDecision must match one candidates[].targetKind: " + targetKind,
                "finalDecision",
                List.of("finalDecision", "candidates"),
                candidates.stream().map(RoutingCandidate::targetKind).toList(),
                earlyDefinition
            );
        }
        Double confidence = confidence(arguments);
        if (confidence == null) {
            confidence = candidates.stream()
                .filter(candidate -> targetKind.equals(candidate.targetKind()))
                .map(RoutingCandidate::confidence)
                .findFirst()
                .orElse(null);
            if (confidence == null) {
                throw error(
                    "CONFIDENCE_REQUIRED",
                    normalizedTool + " requires model confidence or candidates[].confidence for finalDecision.",
                    "confidence",
                    List.of("candidates[].confidence"),
                    List.of("0.0..1.0"),
                    null
                );
            }
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw error(
                "CONFIDENCE_INVALID",
                "confidence must be between 0.0 and 1.0: " + confidence,
                "confidence",
                List.of("confidence"),
                List.of("0.0..1.0"),
                null
            );
        }
        Map<String, Object> trace = trace(arguments);
        if (trace.isEmpty()) {
            throw error(
                "TRACE_REQUIRED",
                normalizedTool + " requires trace object for replayable routing.",
                "trace",
                List.of("trace"),
                List.of("object"),
                null
            );
        }
        TargetKindDefinition definition = null;
        definition = byKind.get(targetKind);
        if (definition == null) {
            throw error(
                "TARGET_KIND_INVALID",
                "Unsupported targetKind: " + targetKind,
                "targetKind",
                List.of("targetKind"),
                allowedTargetKindsForTool(normalizedTool),
                null
            );
        }
        if (!candidates.isEmpty() && candidates.stream().noneMatch(candidate -> targetKind.equals(candidate.targetKind()))) {
            throw error(
                "FINAL_DECISION_NOT_IN_CANDIDATES",
                "finalDecision must match one candidates[].targetKind: " + targetKind,
                "finalDecision",
                List.of("finalDecision", "candidates"),
                candidates.stream().map(RoutingCandidate::targetKind).toList(),
                definition
            );
        }
        if (assetType != null && !definition.assetType().equals(assetType)) {
            throw error(
                "TARGET_KIND_ASSET_TYPE_CONFLICT",
                "targetKind=" + definition.targetKind() + " maps to assetType=" + definition.assetType()
                    + ", but request provided assetType=" + assetType,
                "assetType",
                List.of("targetKind", "assetType"),
                allowedAssetTypesForTool(normalizedTool),
                definition
            );
        }
        if (!definition.allowedTools().contains(normalizedTool)) {
            throw error(
                "TARGET_KIND_TOOL_NOT_ALLOWED",
                "targetKind=" + definition.targetKind() + " is not allowed for " + normalizedTool,
                "targetKind",
                List.of("targetKind"),
                allowedTargetKindsForTool(normalizedTool),
                definition
            );
        }
        String filtersSchemaVersion = firstText(
            text(firstValue(arguments, FILTER_SCHEMA_FIELDS)),
            text(firstValue(filters, FILTER_SCHEMA_FIELDS)),
            FILTERS_SCHEMA_VERSION
        );
        if (!FILTERS_SCHEMA_VERSION.equals(filtersSchemaVersion)) {
            throw error(
                "FILTERS_SCHEMA_INVALID",
                "Unsupported filtersSchemaVersion: " + filtersSchemaVersion,
                "filtersSchemaVersion",
                List.of("filtersSchemaVersion"),
                List.of(FILTERS_SCHEMA_VERSION),
                definition
            );
        }
        validateFilters(definition, filters);
        if (candidates.isEmpty()) {
            candidates = List.of(candidateForDefinition(normalizedTool, definition, confidence, filters));
        }
        RoutingCandidate finalCandidate = candidates.stream()
            .filter(candidate -> targetKind.equals(candidate.targetKind()))
            .findFirst()
            .orElse(candidateForDefinition(normalizedTool, definition, confidence, filters));
        if (!finalCandidate.feasible()) {
            throw error(
                "FINAL_DECISION_NOT_FEASIBLE",
                "finalDecision is not feasible for " + normalizedTool + ": " + targetKind,
                "finalDecision",
                List.of("finalDecision"),
                candidates.stream().filter(RoutingCandidate::feasible).map(RoutingCandidate::targetKind).toList(),
                definition
            );
        }
        String decision = confidence < MIN_CONFIDENCE ? DECISION_REVIEW_REQUIRED : DECISION_ACCEPTED;
        return new Resolution(definition, filtersSchemaVersion, confidence, trace, decision, targetKind, candidates);
    }

    public String normalizeTargetKind(String targetKind) {
        String normalized = normalize(targetKind);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "host", "ssh", "ssh_host", "server", "machine", "linux" -> "host";
            case "database", "db", "sql", "sql_datasource", "datasource" -> "database";
            case "http", "api", "endpoint", "http_endpoint" -> "http";
            case "business_database_query", "database_query", "business_db_query", "sql_template_registry" -> "business_database_query";
            case "document", "doc", "knowledge", "file" -> "document";
            default -> normalized;
        };
    }

    public String targetKindForAssetType(String assetType) {
        TargetKindDefinition definition = byAssetType.get(normalizeAssetType(assetType));
        return definition == null ? null : definition.targetKind();
    }

    public String assetTypeForTargetKind(String targetKind) {
        TargetKindDefinition definition = byKind.get(normalizeTargetKind(targetKind));
        return definition == null ? null : definition.assetType();
    }

    public List<String> allowedTargetKindsForTool(String toolName) {
        String normalizedTool = normalizeToolName(toolName);
        return byKind.values().stream()
            .filter(definition -> definition.allowedTools().contains(normalizedTool))
            .map(TargetKindDefinition::targetKind)
            .toList();
    }

    public Map<String, Object> protocolMetadata(String toolName) {
        String normalizedTool = normalizeToolName(toolName);
        Map<String, Object> mapping = new LinkedHashMap<>();
        byKind.values().forEach(definition -> {
            if (definition.allowedTools().contains(normalizedTool) || "document".equals(definition.targetKind())) {
                mapping.put(definition.targetKind(), definition.assetType());
            }
        });
        return mapOf(
            "requiredMarker", "finalDecision",
            "legacyMarker", "targetKind",
            "routingCandidateSet", mapOf(
                "candidatesPath", "candidates[]",
                "finalDecisionPath", "finalDecision",
                "candidateFields", List.of("targetKind", "confidence")
            ),
            "filtersSchemaVersion", FILTERS_SCHEMA_VERSION,
            "confidenceThreshold", MIN_CONFIDENCE,
            "allowedTargetKinds", allowedTargetKindsForTool(normalizedTool),
            "targetKindToAssetType", mapping,
            "doNotInferFromKeywords", true
        );
    }

    @SuppressWarnings("unchecked")
    private List<RoutingCandidate> routingCandidates(String toolName,
                                                     Map<String, Object> arguments,
                                                     Map<String, Object> filters) {
        Object rawCandidates = firstValue(arguments, CANDIDATE_FIELDS);
        if (!(rawCandidates instanceof List<?> list)) {
            return List.of();
        }
        if (list.isEmpty()) {
            throw error(
                "ROUTING_CANDIDATES_EMPTY",
                "candidates must contain at least one candidate when provided.",
                "candidates",
                List.of("candidates"),
                allowedTargetKindsForTool(toolName),
                null
            );
        }
        List<RoutingCandidate> candidates = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw error(
                    "ROUTING_CANDIDATE_INVALID",
                    "Each routing candidate must be an object.",
                    "candidates",
                    List.of("candidates[].targetKind", "candidates[].confidence"),
                    allowedTargetKindsForTool(toolName),
                    null
                );
            }
            Map<String, Object> candidate = (Map<String, Object>) map;
            String targetKind = normalizeTargetKind(text(firstValue(candidate, TARGET_KIND_FIELDS)));
            Double confidence = confidence(candidate);
            if (targetKind == null || confidence == null || confidence < 0.0 || confidence > 1.0) {
                throw error(
                    "ROUTING_CANDIDATE_INVALID",
                    "Each routing candidate requires targetKind and confidence between 0.0 and 1.0.",
                    "candidates",
                    List.of("candidates[].targetKind", "candidates[].confidence"),
                    allowedTargetKindsForTool(toolName),
                    null
                );
            }
            TargetKindDefinition definition = byKind.get(targetKind);
            boolean known = definition != null;
            boolean permission = known && definition.allowedTools().contains(toolName);
            boolean schema = known && filtersAllowed(definition, filters);
            boolean feasible = known && permission && schema;
            String assetType = known ? definition.assetType() : null;
            long latencyEstimateMs = latencyEstimateMs(targetKind);
            double historicalSuccessRate = historicalSuccessRate(targetKind);
            candidates.add(new RoutingCandidate(
                targetKind,
                assetType,
                confidence,
                feasible,
                candidateReasons(known, permission, schema),
                latencyEstimateMs,
                historicalSuccessRate,
                routingScore(confidence, feasible, latencyEstimateMs, historicalSuccessRate)
            ));
        }
        return candidates.stream()
            .sorted(java.util.Comparator.comparingDouble(RoutingCandidate::score).reversed())
            .toList();
    }

    private RoutingCandidate candidateForDefinition(String toolName,
                                                    TargetKindDefinition definition,
                                                    double confidence,
                                                    Map<String, Object> filters) {
        boolean permission = definition.allowedTools().contains(toolName);
        boolean schema = filtersAllowed(definition, filters);
        boolean feasible = permission && schema;
        long latencyEstimateMs = latencyEstimateMs(definition.targetKind());
        double historicalSuccessRate = historicalSuccessRate(definition.targetKind());
        return new RoutingCandidate(
            definition.targetKind(),
            definition.assetType(),
            confidence,
            feasible,
            candidateReasons(true, permission, schema),
            latencyEstimateMs,
            historicalSuccessRate,
            routingScore(confidence, feasible, latencyEstimateMs, historicalSuccessRate)
        );
    }

    private String finalDecision(Map<String, Object> arguments,
                                 Map<String, Object> filters,
                                 List<RoutingCandidate> candidates) {
        String explicit = firstText(
            text(firstValue(arguments, FINAL_DECISION_FIELDS)),
            text(firstValue(filters, FINAL_DECISION_FIELDS)),
            text(firstValue(arguments, TARGET_KIND_FIELDS)),
            text(firstValue(filters, TARGET_KIND_FIELDS))
        );
        if (explicit != null || candidates.isEmpty()) {
            return explicit;
        }
        return null;
    }

    private boolean filtersAllowed(TargetKindDefinition definition, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Set<String> allowed = definition.allowedFilterFields();
        for (String key : filters.keySet()) {
            if (isProtocolField(key)) {
                continue;
            }
            if (!allowed.contains(normalizeFieldName(key))) {
                return false;
            }
        }
        return true;
    }

    private List<String> candidateReasons(boolean known, boolean permission, boolean schema) {
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add(known ? "registry_target_kind_known" : "registry_target_kind_unknown");
        reasons.add(permission ? "tool_allowed" : "tool_not_allowed");
        reasons.add(schema ? "filters_schema_match" : "filters_schema_mismatch");
        reasons.add("asset_existence_deferred_to_retrieval");
        return reasons;
    }

    private long latencyEstimateMs(String targetKind) {
        return switch (normalizeTargetKind(targetKind)) {
            case "http" -> 60L;
            case "business_database_query" -> 80L;
            case "database" -> 120L;
            case "host" -> 150L;
            default -> 200L;
        };
    }

    private double historicalSuccessRate(String targetKind) {
        return switch (normalizeTargetKind(targetKind)) {
            case "database" -> 0.86;
            case "http" -> 0.82;
            case "business_database_query" -> 0.88;
            case "host" -> 0.80;
            default -> 0.50;
        };
    }

    private double routingScore(double confidence,
                                boolean feasible,
                                long latencyEstimateMs,
                                double historicalSuccessRate) {
        double latencyScore = 1.0 / (1.0 + (latencyEstimateMs / 100.0));
        double feasibilityScore = feasible ? 1.0 : 0.0;
        return Math.round((confidence * 0.60
            + feasibilityScore * 0.20
            + historicalSuccessRate * 0.15
            + latencyScore * 0.05) * 10000.0) / 10000.0;
    }

    private void validateFilters(TargetKindDefinition definition, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        Set<String> allowed = definition.allowedFilterFields();
        for (String key : filters.keySet()) {
            if (isProtocolField(key)) {
                continue;
            }
            String normalizedKey = normalizeFieldName(key);
            if (!allowed.contains(normalizedKey)) {
                throw error(
                    "FILTER_FIELD_NOT_ALLOWED",
                    "Filter field is not allowed for targetKind=" + definition.targetKind() + ": " + key,
                    key,
                    List.of("targetKind", "filtersSchemaVersion"),
                    List.copyOf(allowed),
                    definition
                );
            }
        }
    }

    private static Map<String, TargetKindDefinition> definitionsByKind() {
        Map<String, TargetKindDefinition> definitions = new LinkedHashMap<>();
        add(definitions, "host", "ssh_host", Set.of("asset_query", "template_query"), Set.of(
            "assetname", "asset_name", "name", "env", "environment", "cluster", "namespace",
            "target", "targettype", "target_type", "service", "labels", "intent", "goal", "category",
            "bilingualintent", "bilingualquery", "bilingualsearch", "intentzh", "intenten",
            "intentaliases", "keywords", "keyword", "queryterms", "searchterms", "retrievalsignals",
            "intentcandidates", "intent_candidates", "queries", "expandedqueries", "expanded_queries",
            "template", "templateid", "template_id", "view", "language", "querylanguage", "locale"
        ));
        add(definitions, "database", "sql_datasource", Set.of("asset_query", "template_query"), Set.of(
            "assetname", "asset_name", "name", "env", "environment", "cluster", "namespace",
            "database", "schema", "catalog", "databasetype", "database_type", "dbtype", "db_type",
            "dialect", "databaserole", "database_role", "service", "target", "labels",
            "intent", "goal", "category", "tablename", "table_name", "template", "templateid",
            "template_id", "bilingualintent", "bilingualquery", "bilingualsearch", "intentzh", "intenten",
            "intentaliases", "keywords", "keyword", "queryterms", "searchterms", "retrievalsignals",
            "intentcandidates", "intent_candidates", "queries", "expandedqueries", "expanded_queries",
            "view", "language", "querylanguage", "locale"
        ));
        add(definitions, "http", "http_endpoint", Set.of("asset_query", "template_query"), Set.of(
            "assetname", "asset_name", "name", "env", "environment", "cluster", "namespace",
            "service", "target", "labels", "intent", "goal", "category", "template",
            "templateid", "template_id", "bilingualintent", "bilingualquery", "bilingualsearch",
            "intentzh", "intenten", "intentaliases", "keywords", "keyword", "queryterms",
            "searchterms", "retrievalsignals", "intentcandidates", "intent_candidates",
            "queries", "expandedqueries", "expanded_queries", "view", "language", "querylanguage", "locale"
        ));
        add(definitions, "business_database_query", "database_query", Set.of("template_query"), Set.of(
            "assetname", "asset_name", "name", "env", "environment", "intent", "goal", "category",
            "template", "templateid", "template_id", "databasetype", "database_type", "dbtype",
            "db_type", "dialect", "businessgroup", "business_group", "group", "groupname",
            "group_name", "groupdescription", "group_description", "labels", "bilingualintent", "bilingualquery", "bilingualsearch",
            "intentzh", "intenten", "intentaliases", "keywords", "keyword", "queryterms",
            "searchterms", "retrievalsignals", "intentcandidates", "intent_candidates",
            "queries", "expandedqueries", "expanded_queries", "view", "language", "querylanguage", "locale"
        ));
        add(definitions, "document", "document_search", Set.of("document_search"), Set.of(
            "assetname", "asset_name", "name", "intent", "goal", "category", "labels",
            "bilingualintent", "bilingualquery", "intentzh", "intenten", "intentaliases",
            "keywords", "keyword", "queryterms", "searchterms", "retrievalsignals",
            "intentcandidates", "intent_candidates", "queries", "expandedqueries", "expanded_queries",
            "view", "locale"
        ));
        return Map.copyOf(definitions);
    }

    private static void add(Map<String, TargetKindDefinition> definitions,
                            String targetKind,
                            String assetType,
                            Set<String> allowedTools,
                            Set<String> allowedFilterFields) {
        definitions.put(targetKind, new TargetKindDefinition(
            targetKind,
            assetType,
            Set.copyOf(allowedTools),
            Set.copyOf(allowedFilterFields)
        ));
    }

    private static Map<String, TargetKindDefinition> definitionsByAssetType(Map<String, TargetKindDefinition> byKind) {
        Map<String, TargetKindDefinition> definitions = new LinkedHashMap<>();
        byKind.values().forEach(definition -> definitions.put(definition.assetType(), definition));
        return Map.copyOf(definitions);
    }

    private List<String> allowedAssetTypesForTool(String toolName) {
        String normalizedTool = normalizeToolName(toolName);
        return byKind.values().stream()
            .filter(definition -> definition.allowedTools().contains(normalizedTool))
            .map(TargetKindDefinition::assetType)
            .toList();
    }

    private TargetKindException error(String code,
                                      String message,
                                      String field,
                                      List<String> requiredFields,
                                      List<String> allowedValues,
                                      TargetKindDefinition definition) {
        Map<String, Object> details = mapOf(
            "code", code,
            "message", message,
            "field", field,
            "required_fields", requiredFields,
            "allowed_values", allowedValues,
            "filtersSchemaVersion", FILTERS_SCHEMA_VERSION,
            "confidenceThreshold", MIN_CONFIDENCE,
            "repair_hint", "Return a corrected tool call with candidates[], finalDecision, filters, trace, and filtersSchemaVersion="
                + FILTERS_SCHEMA_VERSION + "; use document_search for targetKind=document."
        );
        if (definition != null) {
            details.put("targetKind", definition.targetKind());
            details.put("assetType", definition.assetType());
            details.put("allowed_tools", definition.allowedTools());
            details.put("allowed_filter_fields", definition.allowedFilterFields());
        }
        return new TargetKindException(code, message, details);
    }

    private static boolean isAlias(String key, List<String> aliases) {
        String normalized = normalizeFieldName(key);
        return aliases.stream().map(TargetKindRegistry::normalizeFieldName).anyMatch(normalized::equals);
    }

    private static boolean isProtocolField(String key) {
        return isAlias(key, TARGET_KIND_FIELDS)
            || isAlias(key, FILTER_SCHEMA_FIELDS)
            || isAlias(key, FINAL_DECISION_FIELDS)
            || isAlias(key, CANDIDATE_FIELDS);
    }

    private static String normalizeAssetType(String assetType) {
        String normalized = normalize(assetType);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "host", "ssh", "sshhost" -> "ssh_host";
            case "database", "db", "sql", "sqldatasource", "datasource" -> "sql_datasource";
            case "http", "api", "endpoint", "httpendpoint" -> "http_endpoint";
            case "businessdatabasequery", "business_database_query", "business_db_query", "sqltemplateregistry",
                "sql_template_registry" -> "database_query";
            default -> normalized;
        };
    }

    private static String normalizeToolName(String toolName) {
        String normalized = normalize(toolName);
        if (normalized == null) {
            return "";
        }
        for (String known : List.of("asset_query", "template_query", "document_search")) {
            if (normalized.equals(known) || normalized.endsWith("_" + known)) {
                return known;
            }
        }
        return normalized;
    }

    private static String normalizeFieldName(String key) {
        String normalized = normalize(key);
        return normalized == null ? "" : normalized.replace("_", "");
    }

    private static Object firstValue(Map<String, Object> map, List<String> keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasFilterEnvelope(Map<String, Object> arguments) {
        Object filters = firstValue(arguments, List.of("filters", "executionContext", "mcpExecutionContext"));
        return filters instanceof Map<?, ?>;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trace(Map<String, Object> arguments) {
        Object value = firstValue(arguments, TRACE_FIELDS);
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private static Double confidence(Map<String, Object> arguments) {
        Object value = firstValue(arguments, List.of("confidence", "targetConfidence", "routingConfidence"));
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    public record TargetKindDefinition(String targetKind,
                                       String assetType,
                                       Set<String> allowedTools,
                                       Set<String> allowedFilterFields) {
    }

    public record Resolution(TargetKindDefinition definition,
                             String filtersSchemaVersion,
                             double confidence,
                             Map<String, Object> trace,
                             String decision,
                             String finalDecision,
                             List<RoutingCandidate> candidates) {
        public boolean reviewRequired() {
            return DECISION_REVIEW_REQUIRED.equals(decision);
        }
    }

    public record RoutingCandidate(String targetKind,
                                   String assetType,
                                   double confidence,
                                   boolean feasible,
                                   List<String> feasibilityReasons,
                                   long latencyEstimateMs,
                                   double historicalSuccessRate,
                                   double score) {
    }

    public static class TargetKindException extends IllegalArgumentException {

        private final String code;
        private final Map<String, Object> details;

        public TargetKindException(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = new LinkedHashMap<>(details);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> details() {
            return new LinkedHashMap<>(details);
        }
    }
}
