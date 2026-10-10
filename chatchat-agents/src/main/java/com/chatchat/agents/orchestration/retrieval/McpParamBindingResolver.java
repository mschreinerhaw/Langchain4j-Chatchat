package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes planner arguments using published MCP workflow and routing contracts.
 */
public class McpParamBindingResolver {

    private final DiscoveryParameterNormalizer discoveryParameterNormalizer =
        new DiscoveryParameterNormalizer();
    private final McpBindingPolicyRegistry bindingPolicies;

    public McpParamBindingResolver() {
        this(new McpBindingPolicyRegistry());
    }

    public McpParamBindingResolver(McpBindingPolicyRegistry bindingPolicies) {
        this.bindingPolicies = java.util.Objects.requireNonNull(bindingPolicies);
    }

    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?iu)(?:\\benv(?:ironment)?\\b|\\u73af\\u5883)\\s*(?:[:=]|\\u4e3a|\\u662f)\\s*"
            + "(DEV|TEST|UAT|PROD|\\u5f00\\u53d1|\\u6d4b\\u8bd5|\\u9884\\u53d1|\\u751f\\u4ea7)"
    );
    private static final Pattern ENV_QUALIFIER_PATTERN = Pattern.compile(
        "(?iu)(DEV|TEST|UAT|PROD|\\u5f00\\u53d1|\\u6d4b\\u8bd5|\\u9884\\u53d1|\\u751f\\u4ea7)\\s*"
            + "(?:\\u73af\\u5883|\\u96c6\\u7fa4|\\benv(?:ironment)?\\b)"
    );
    private static final Pattern ENV_ENGLISH_CONTEXT_PATTERN = Pattern.compile(
        "(?iu)\\b(?:in|on|under)\\s+(?:the\\s+)?(DEV|TEST|UAT|PROD)"
            + "(?:\\s+(?:env(?:ironment)?|cluster))?\\b"
    );

    public static final String STATUS_KEY = "__runtimeParamBindingStatus";
    public static final String ERROR_KEY = "__runtimeParamBindingError";
    public static final String CODE_KEY = "__runtimeParamBindingCode";

    private static final Set<String> MCP_CATEGORIES = Set.of("mcp");
    private static final String FILTERS_SCHEMA_VERSION = AgentProtocolCatalog.TARGET_FILTERS;
    private static final double TARGET_KIND_CONFIDENCE_THRESHOLD = 0.60;
    private static final double INTENT_RETRIEVAL_THRESHOLD = 0.75;

    public Map<String, Object> resolve(String toolName,
                                ToolMetadata metadata,
                                Map<String, Object> arguments,
                                String userQuery) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (!isMcpTool(toolName, metadata)) {
            return values;
        }
        McpBindingPolicyRegistry.Policy binding = bindingPolicies.resolve(metadata);
        if (binding == McpBindingPolicyRegistry.Policy.PASSTHROUGH) return values;
        if (binding == McpBindingPolicyRegistry.Policy.REJECTED) {
            return denied(values, "Active MCP tool contract has no supported execution binding policy.");
        }
        McpArgumentBindingFieldPolicy fields = McpArgumentBindingFieldPolicy.from(metadata);
        if (fields == null) return denied(values, "Active MCP tool contract has no valid argumentBindingPolicy.");
        return switch (binding) {
            case ASSET_DISCOVERY -> bindDiscoveryQuery(toolName, metadata, values, userQuery, false, fields);
            case TEMPLATE_DISCOVERY -> bindDiscoveryQuery(toolName, metadata, values, userQuery, true, fields);
            case SHELL_EXECUTION -> bindLinuxCommand(values, userQuery, fields);
            case HTTP_EXECUTION -> bindHttpRequest(values, userQuery, fields);
            case SQL_EXECUTION -> bindSqlQuery(values, userQuery, fields);
            case PASSTHROUGH -> values;
            case REJECTED -> denied(values, "Unsupported MCP execution binding policy.");
        };
    }

    private Map<String, Object> bindSqlQuery(Map<String, Object> values, String userQuery,
                                             McpArgumentBindingFieldPolicy fields) {
        String forbidden = firstPresentField(values, fields.concreteTargetFields());
        if (forbidden != null) {
            return denied(values, "Concrete datasource target is not allowed for sql_query_execute: " + forbidden);
        }
        renameFirst(values, "template", "templateId", "template_id", "sqlTemplate", "sql_template");
        Map<String, Object> context = logicalExecutionContext(values, userQuery, fields);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        if (!hasText(values.get("purpose")) && hasText(userQuery)) {
            values.put("purpose", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindLinuxCommand(Map<String, Object> values, String userQuery,
                                                 McpArgumentBindingFieldPolicy fields) {
        String forbidden = firstPresentField(values, fields.concreteTargetFields());
        if (forbidden != null) {
            return denied(values, "Concrete execution target is not allowed for linux_command_execute: " + forbidden);
        }
        String rawExecution = firstPresentField(values, fields.rawExecutionFields());
        if (rawExecution != null) {
            return denied(values, "Raw execution field is not allowed for linux_command_execute: " + rawExecution
                + ". Use a registered template plus parameters.");
        }

        renameFirst(values, "template", "templateId", "template_id", "commandTemplate", "command_template");
        Map<String, Object> context = logicalExecutionContext(values, userQuery, fields);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        normalizeParameters(values, userQuery);
        if (!hasText(values.get("reason")) && hasText(userQuery)) {
            values.put("reason", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindHttpRequest(Map<String, Object> values, String userQuery,
                                                McpArgumentBindingFieldPolicy fields) {
        String forbidden = firstPresentField(values, fields.concreteTargetFields());
        if (forbidden != null) {
            return denied(values, "Concrete endpoint target is not allowed for http_request_execute: " + forbidden);
        }
        renameFirst(values, "template", "templateId", "template_id", "endpoint", "endpointName");
        Map<String, Object> context = logicalExecutionContext(values, userQuery, fields);
        if (!context.isEmpty()) {
            values.put("executionContext", context);
        }
        normalizeParameters(values, userQuery);
        if (!hasText(values.get("reason")) && hasText(userQuery)) {
            values.put("reason", trim(userQuery));
        }
        values.remove("query");
        return values;
    }

    private Map<String, Object> bindDiscoveryQuery(String toolName,
                                                   ToolMetadata metadata,
                                                   Map<String, Object> values,
                                                   String userQuery,
                                                   boolean templateQuery,
                                                   McpArgumentBindingFieldPolicy fields) {
        PublishedDiscoveryContract publishedContract = publishedDiscoveryContract(metadata);
        String forbidden = firstPresentField(values, fields.concreteTargetFields());
        if (forbidden != null) {
            return denied(values, "Concrete target field is not allowed for discovery: " + forbidden);
        }
        if (templateQuery) {
            String rawExecution = firstPresentField(values, fields.rawExecutionFields());
            if (rawExecution != null) {
                return denied(values, "Raw execution field is not allowed for template_query: " + rawExecution);
            }
        }

        String targetKind = removeTargetKind(values, fields);
        Object rawCandidates = firstPresent(values, "candidates", "routingCandidates", "routing_candidates");
        String finalDecision = firstText(firstPresent(values, "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind"));
        if (targetKind == null) {
            targetKind = finalDecision;
        }
        String toolTargetKind = publishedContract.forcedTargetKind();
        if (toolTargetKind != null) {
            targetKind = toolTargetKind;
            forceDiscoveryTargetKind(values, toolTargetKind, rawCandidates);
            rawCandidates = firstPresent(values, "candidates", "routingCandidates", "routing_candidates");
            finalDecision = firstText(firstPresent(values, "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind"));
        }
        Object explicitFilterEnvelope = firstPresent(values, "filters", "executionContext", "mcpExecutionContext");
        boolean synthesizedLegacyFilterEnvelope = false;
        if (!(explicitFilterEnvelope instanceof Map<?, ?>)) {
            if (hasText(values.get("query"))
                && (toolTargetKind != null || publishedContract.accepts("filters"))) {
                targetKind = firstNonBlank(targetKind, toolTargetKind);
                Map<String, Object> filters = new LinkedHashMap<>();
                inferLogicalContext(userQuery).forEach(filters::putIfAbsent);
                if (hasText(userQuery)) {
                    filters.putIfAbsent("intent", trim(userQuery));
                }
                values.put("filters", filters);
                if (publishedContract.requiresRoutingDecision() && hasText(targetKind)) {
                    values.putIfAbsent("candidates", List.of(Map.of("targetKind", targetKind, "confidence", 0.9)));
                    values.putIfAbsent("finalDecision", targetKind);
                    values.putIfAbsent("confidence", 0.9);
                }
                synthesizedLegacyFilterEnvelope = true;
                explicitFilterEnvelope = filters;
                rawCandidates = firstPresent(values, "candidates", "routingCandidates", "routing_candidates");
                finalDecision = firstText(firstPresent(values, "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind"));
            }
        }
        if (!(explicitFilterEnvelope instanceof Map<?, ?>)
            && publishedContract.accepts("filters")
            && !publishedContract.requires("filters")) {
            values.put("filters", Map.of());
            explicitFilterEnvelope = values.get("filters");
        }
        if (!(explicitFilterEnvelope instanceof Map<?, ?>)) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires explicit filters object, even when it is empty.");
        }
        DiscoveryParameterNormalizer.Normalization normalizedParameters =
            discoveryParameterNormalizer.normalize(values, inferLogicalContext(userQuery), userQuery, fields);
        Map<String, Object> filters = new LinkedHashMap<>(normalizedParameters.filters());
        removeForbidden(filters, fields);
        // A single canonical envelope prevents the MCP server from applying a second,
        // different precedence order to stale aliases.
        values.remove("executionContext");
        values.remove("mcpExecutionContext");
        if (!normalizedParameters.conflicts().isEmpty() || !normalizedParameters.repairs().isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(McpParamBindingResolver.class).info(
                "Discovery parameters normalized tool={} conflicts={} repairs={} temporalConstraints={} provenance={}",
                toolName,
                normalizedParameters.conflicts(),
                normalizedParameters.repairs(),
                normalizedParameters.temporalConstraints(),
                normalizedParameters.provenance()
            );
        }
        if (targetKind == null) {
            targetKind = removeTargetKind(filters, fields);
        }
        if (templateQuery && !hasText(firstPresent(filters, "intent", "goal", "category"))) {
            if (hasText(userQuery)) {
                filters.put("intent", trim(userQuery));
            }
        }
        enrichRetrievalTerms(filters, values, userQuery);
        repairFiltersFromPublishedContract(metadata, filters);
        if (!filters.isEmpty()) {
            values.put("filters", filters);
        } else if (values.containsKey("query")) {
            values.put("filters", Map.of());
        }
        if (publishedContract.accepts("filtersSchemaVersion")) {
            values.putIfAbsent("filtersSchemaVersion", FILTERS_SCHEMA_VERSION);
        }
        if (!publishedContract.requiresRoutingDecision()) {
            return finishPublishedScopedDiscovery(values, toolName, targetKind, publishedContract,
                synthesizedLegacyFilterEnvelope);
        }
        if (targetKind == null) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires explicit finalDecision/targetKind. Allowed targetKind values are "
                + publishedContract.allowedKindsDescription() + ".");
        }
        if (hasText(publishedContract.forcedAssetType())) {
            values.put("assetType", publishedContract.forcedAssetType());
        }
        Double confidence = confidence(values.get("confidence"));
        if (confidence == null) {
            confidence = candidateConfidence(rawCandidates, targetKind);
            if (confidence != null) {
                values.put("confidence", confidence);
            }
        }
        if (confidence == null) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires confidence between 0.0 and 1.0.");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            return denied(values, "confidence must be between 0.0 and 1.0: " + confidence);
        }
        ensureRuntimeRoutingTrace(values, toolName, targetKind, confidence,
            synthesizedLegacyFilterEnvelope ? "agent_tool_argument_resolver" : "agent_runtime_param_binding",
            toolTargetKind == null ? "planner_decision" : "published_tool_contract");
        if (!(firstPresent(values, "trace", "routingTrace", "routing_trace") instanceof Map<?, ?> trace) || trace.isEmpty()) {
            return denied(values, (templateQuery ? "template_query" : "asset_query")
                + " requires trace object for replayable routing.");
        }
        if (!hasText(values.get("assetType"))) {
            String assetType = publishedContract.assetTypeFor(targetKind);
            if (assetType != null) {
                values.put("assetType", assetType);
                values.put("targetKind", normalizeRoutingValue(targetKind));
                values.putIfAbsent("finalDecision", normalizeRoutingValue(targetKind));
            } else if (hasText(targetKind)) {
                return denied(values, "Unsupported targetKind for " + (templateQuery ? "template_query" : "asset_query")
                    + ": " + targetKind + ". Allowed targetKind values are "
                    + publishedContract.allowedKindsDescription() + ".");
            } else {
                return denied(values, (templateQuery ? "template_query" : "asset_query")
                    + " requires explicit finalDecision/targetKind. Allowed targetKind values are "
                    + publishedContract.allowedKindsDescription() + ".");
            }
        } else if (targetKind != null) {
            String normalizedTargetKind = normalizeRoutingValue(targetKind);
            String expectedAssetType = publishedContract.assetTypeFor(normalizedTargetKind);
            if (expectedAssetType == null) {
                return denied(values, "Unsupported targetKind for " + (templateQuery ? "template_query" : "asset_query")
                    + ": " + targetKind + ". Allowed targetKind values are "
                    + publishedContract.allowedKindsDescription() + ".");
            }
            String providedAssetType = normalizeRoutingValue(firstText(values.get("assetType")));
            if (providedAssetType != null && !providedAssetType.equals(expectedAssetType)) {
                return denied(values, "targetKind=" + normalizedTargetKind + " maps to assetType="
                    + expectedAssetType + ", but request provided assetType=" + providedAssetType + ".");
            }
            values.put("assetType", providedAssetType);
            values.put("targetKind", normalizedTargetKind);
            values.putIfAbsent("finalDecision", normalizedTargetKind);
        }
        if (confidence < TARGET_KIND_CONFIDENCE_THRESHOLD) {
            return reviewRequired(values, "confidence below routing threshold: " + confidence);
        }
        values.putIfAbsent("limit", 10);
        values.remove("query");
        return values;
    }

    private void ensureRuntimeRoutingTrace(Map<String, Object> values,
                                           String toolName,
                                           String targetKind,
                                           Double confidence,
                                           String source,
                                           String decisionSource) {
        Object existing = firstPresent(values, "trace", "routingTrace", "routing_trace");
        if (existing instanceof Map<?, ?> trace && !trace.isEmpty()) {
            return;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schemaVersion", AgentProtocolCatalog.ROUTING_TRACE);
        trace.put("source", source);
        trace.put("toolName", toolName == null ? "" : toolName);
        trace.put("finalDecision", firstNonBlank(normalizeRoutingValue(targetKind), ""));
        if (confidence != null) {
            trace.put("confidence", confidence);
        }
        trace.put("decisionSource", decisionSource);
        trace.put("filtersSchemaVersion", FILTERS_SCHEMA_VERSION);
        values.put("trace", Map.copyOf(trace));
        values.remove("routingTrace");
        values.remove("routing_trace");
    }

    private Map<String, Object> finishPublishedScopedDiscovery(Map<String, Object> values,
                                                               String toolName,
                                                               String targetKind,
                                                               PublishedDiscoveryContract contract,
                                                               boolean synthesizedLegacyFilterEnvelope) {
        Double confidence = confidence(values.get("confidence"));
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            return denied(values, "confidence must be between 0.0 and 1.0: " + confidence);
        }
        if (contract.accepts("trace")) {
            Double traceConfidence = confidence;
            if (traceConfidence == null && hasText(contract.forcedTargetKind())) {
                traceConfidence = Double.valueOf(1.0);
            }
            ensureRuntimeRoutingTrace(values, toolName, targetKind,
                traceConfidence,
                synthesizedLegacyFilterEnvelope ? "agent_tool_argument_resolver" : "agent_runtime_param_binding",
                hasText(contract.forcedTargetKind()) ? "published_tool_contract" : "published_service_scope");
        }
        removeUnsupportedRoutingFields(values, contract);
        if (contract.accepts("limit")) {
            values.putIfAbsent("limit", 10);
        }
        values.remove("query");
        return values;
    }

    private void removeUnsupportedRoutingFields(Map<String, Object> values,
                                                PublishedDiscoveryContract contract) {
        for (String field : List.of(
            "candidates", "routingCandidates", "routing_candidates",
            "finalDecision", "final_decision", "selectedTargetKind", "selected_target_kind",
            "targetKind", "target_kind", "assetType", "asset_type", "confidence",
            "trace", "routingTrace", "routing_trace", "filtersSchemaVersion", "filters_schema_version")) {
            if (!contract.accepts(field)) {
                values.remove(field);
            }
        }
    }

    private PublishedDiscoveryContract publishedDiscoveryContract(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return PublishedDiscoveryContract.legacy();
        }
        Map<String, Object> extra = metadata.getMetadata();
        Map<String, Object> inputSchema = asMap(extra.get("inputSchema"));
        Map<String, Object> properties = asMap(inputSchema.get("properties"));
        Map<String, Object> mcpMeta = asMap(extra.get("mcpToolMeta"));
        if (inputSchema.isEmpty() && mcpMeta.isEmpty()) {
            return PublishedDiscoveryContract.legacy();
        }
        java.util.LinkedHashSet<String> accepted = new java.util.LinkedHashSet<>();
        properties.keySet().stream().map(this::canonicalField).filter(key -> !key.isBlank()).forEach(accepted::add);
        java.util.LinkedHashSet<String> required = new java.util.LinkedHashSet<>();
        if (inputSchema.get("required") instanceof Iterable<?> fields) {
            for (Object field : fields) {
                String canonical = canonicalField(field == null ? null : String.valueOf(field));
                if (!canonical.isBlank()) {
                    required.add(canonical);
                }
            }
        }
        Map<String, Object> routingProtocol = asMap(mcpMeta.get("routingProtocol"));
        Map<String, Object> toolBoundary = asMap(mcpMeta.get("toolBoundary"));
        String forcedTargetKind = firstNonBlank(
            firstText(firstPresent(routingProtocol, "forcedTargetKind", "targetKind")),
            firstNonBlank(
                firstText(firstPresent(toolBoundary, "forcedTargetKind", "targetKind")),
                firstText(firstPresent(mcpMeta, "targetKind"))));
        String forcedAssetType = firstNonBlank(
            firstText(firstPresent(routingProtocol, "forcedAssetType", "assetType")),
            firstNonBlank(
                firstText(firstPresent(toolBoundary, "forcedAssetType", "assetType")),
                firstText(firstPresent(mcpMeta, "assetType"))));
        Map<String, String> targetKindToAssetType = new LinkedHashMap<>();
        asMap(routingProtocol.get("targetKindToAssetType")).forEach((kind, assetType) -> {
            String normalizedKind = normalizeRoutingValue(kind);
            String normalizedAssetType = normalizeRoutingValue(firstText(assetType));
            if (normalizedKind != null && normalizedAssetType != null) {
                targetKindToAssetType.put(normalizedKind, normalizedAssetType);
            }
        });
        Set<String> allowedTargetKinds = new java.util.LinkedHashSet<>();
        if (routingProtocol.get("allowedTargetKinds") instanceof Iterable<?> kinds) {
            for (Object kind : kinds) {
                String normalizedKind = normalizeRoutingValue(firstText(kind));
                if (normalizedKind != null) allowedTargetKinds.add(normalizedKind);
            }
        }
        boolean requiresRoutingDecision = accepted.contains(canonicalField("finalDecision"))
            || accepted.contains(canonicalField("candidates"));
        return new PublishedDiscoveryContract(true, Set.copyOf(accepted), Set.copyOf(required),
            forcedTargetKind, forcedAssetType, Map.copyOf(targetKindToAssetType),
            Set.copyOf(allowedTargetKinds), requiresRoutingDecision);
    }

    /**
     * Keeps discovery arguments aligned with the filter contract published by the remote MCP tool.
     * Unknown semantic selectors are folded into retrievalSignals when the contract supports that
     * field; concrete targets are simply discarded by the earlier governance checks.
     */
    private void repairFiltersFromPublishedContract(ToolMetadata metadata, Map<String, Object> filters) {
        Set<String> allowed = publishedFilterFields(metadata);
        if (filters == null || filters.isEmpty() || allowed.isEmpty()) {
            return;
        }
        List<String> semanticSignals = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new ArrayList<>(filters.entrySet())) {
            if (allowed.contains(canonicalField(entry.getKey()))) {
                continue;
            }
            filters.remove(entry.getKey());
            addSemanticSignals(semanticSignals, entry.getKey(), entry.getValue());
        }
        if (!semanticSignals.isEmpty() && allowed.contains(canonicalField("retrievalSignals"))) {
            filters.put("retrievalSignals", mergeTerms(filters.get("retrievalSignals"), semanticSignals));
        }
    }

    private Set<String> publishedFilterFields(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return Set.of();
        }
        Map<String, Object> toolMetadata = asMap(metadata.getMetadata().get("mcpToolMeta"));
        Map<String, Object> routingProtocol = asMap(toolMetadata.get("routingProtocol"));
        Object rawAllowed = routingProtocol.get("allowedFilterFields");
        if (!(rawAllowed instanceof Iterable<?> values)) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> allowed = new java.util.LinkedHashSet<>();
        for (Object value : values) {
            String canonical = canonicalField(value == null ? null : String.valueOf(value));
            if (!canonical.isBlank()) {
                allowed.add(canonical);
            }
        }
        return Set.copyOf(allowed);
    }

    private void addSemanticSignals(List<String> signals, String field, Object value) {
        if (signals == null || value == null || value instanceof Map<?, ?>) {
            return;
        }
        List<String> values = new ArrayList<>();
        addTerms(values, value);
        for (String item : values) {
            addTerms(signals, field + ":" + item);
            addTerms(signals, item);
        }
    }

    private String canonicalField(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> logicalExecutionContext(Map<String, Object> values, String userQuery,
                                                        McpArgumentBindingFieldPolicy fields) {
        Map<String, Object> context = new LinkedHashMap<>();
        Object existing = firstPresent(values, "executionContext", "mcpExecutionContext");
        if (existing instanceof Map<?, ?> map) {
            context.putAll((Map<String, Object>) map);
        }
        for (String key : fields.logicalContextKeys()) {
            Object value = values.remove(key);
            if (value != null && hasText(value)) {
                context.putIfAbsent(key, value);
            }
        }
        inferLogicalContext(userQuery).forEach(context::putIfAbsent);
        removeForbidden(context, fields);
        return context;
    }

    private void enrichRetrievalTerms(Map<String, Object> filters, Map<String, Object> values, String userQuery) {
        if (filters == null) {
            return;
        }
        Object rawCandidates = firstPresent(filters, "intentCandidates", "intent_candidates");
        if (rawCandidates == null) {
            rawCandidates = firstPresent(values, "intentCandidates", "intent_candidates");
        }
        List<String> selectedIntentTerms = selectedIntentTerms(rawCandidates);
        // Task text remains model context; only model-selected retrieval units belong here.
        List<String> queryTerms = mergeTerms(filters.get("queryTerms"), filters.get("keywords"), selectedIntentTerms);
        if (!queryTerms.isEmpty()) {
            filters.put("queryTerms", queryTerms);
        }
        List<String> retrievalSignals = mergeTerms(filters.get("retrievalSignals"), queryTerms);
        if (!retrievalSignals.isEmpty()) {
            filters.put("retrievalSignals", retrievalSignals);
        }
        if (rawCandidates instanceof List<?> candidates && !candidates.isEmpty()
            && !filters.containsKey("intentScoring")) {
            filters.put("intentScoring", Map.of(
                "strategy", "threshold_intent_ensemble",
                "threshold", INTENT_RETRIEVAL_THRESHOLD,
                "fallback", "model_supplied_search_terms_only",
                "selectedTerms", selectedIntentTerms,
                "candidateCount", candidates.size()
            ));
        }
    }

    private List<String> selectedIntentTerms(Object rawCandidates) {
        if (!(rawCandidates instanceof List<?> candidates) || candidates.isEmpty()) {
            return List.of();
        }
        List<IntentCandidate> ranked = candidates.stream()
            .map(this::intentCandidate)
            .filter(candidate -> !candidate.terms().isEmpty())
            .sorted(Comparator.comparingDouble(IntentCandidate::score).reversed())
            .toList();
        List<IntentCandidate> selected = ranked.stream()
            .filter(candidate -> candidate.score() >= INTENT_RETRIEVAL_THRESHOLD)
            .toList();
        List<String> terms = new ArrayList<>();
        selected.forEach(candidate -> addTerms(terms, candidate.terms()));
        return terms;
    }

    private IntentCandidate intentCandidate(Object item) {
        if (item instanceof Map<?, ?> map) {
            Map<String, Object> candidate = asMap(item);
            List<String> terms = new ArrayList<>();
            for (String key : List.of("intent", "query", "term", "text", "label", "name",
                "queryTerms", "searchTerms", "retrievalSignals", "queries", "expandedQueries",
                "expanded_queries", "keywords", "intentAliases", "bilingualIntent")) {
                addTerms(terms, map.get(key));
            }
            Double score = confidence(firstPresent(candidate, "score", "confidence", "matchScore", "match_score"));
            return new IntentCandidate(List.copyOf(terms), score == null ? 0.0D : score);
        }
        List<String> terms = new ArrayList<>();
        addTerms(terms, item);
        return new IntentCandidate(List.copyOf(terms), 0.0D);
    }

    private List<String> mergeTerms(Object existing, Object... additions) {
        List<String> terms = new ArrayList<>();
        addTerms(terms, existing);
        if (additions != null) {
            for (Object addition : additions) {
                addTerms(terms, addition);
            }
        }
        return terms;
    }

    private void addTerms(List<String> terms, Object value) {
        if (terms == null || value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> addTerms(terms, item));
            return;
        }
        String text = firstText(value);
        if (text != null && terms.stream().noneMatch(existing -> existing.equalsIgnoreCase(text))) {
            terms.add(text);
        }
    }

    @SuppressWarnings("unchecked")
    private void normalizeParameters(Map<String, Object> values, String userQuery) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Object existing = values.get("parameters");
        if (existing instanceof Map<?, ?> map) {
            parameters.putAll((Map<String, Object>) map);
        }
        moveIfPresent(values, parameters, "serviceName", "service_name");
        moveIfPresent(values, parameters, "path", "filePath", "file_path", "logPath", "log_path");
        moveIfPresent(values, parameters, "lines", "tailLines", "tail_lines", "limit");
        moveIfPresent(values, parameters, "keyword", "keywords", "pattern");
        if (!parameters.isEmpty()) {
            values.put("parameters", parameters);
        }
    }

    private Map<String, Object> inferLogicalContext(String query) {
        Map<String, Object> context = new LinkedHashMap<>();
        String env = inferEnvironment(query);
        if (env != null) {
            context.put("env", env);
        }
        return context;
    }

    private String inferEnvironment(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (Pattern pattern : List.of(
            ENV_ASSIGNMENT_PATTERN,
            ENV_QUALIFIER_PATTERN,
            ENV_ENGLISH_CONTEXT_PATTERN
        )) {
            Matcher matcher = pattern.matcher(query);
            if (matcher.find()) {
                return canonicalEnvironment(matcher.group(1));
            }
        }
        return null;
    }

    private String canonicalEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "\u5f00\u53d1" -> "DEV";
            case "TEST", "\u6d4b\u8bd5" -> "TEST";
            case "UAT", "\u9884\u53d1" -> "UAT";
            case "PROD", "\u751f\u4ea7" -> "PROD";
            default -> null;
        };
    }

    private void forceDiscoveryTargetKind(Map<String, Object> values, String targetKind, Object rawCandidates) {
        String normalizedTargetKind = normalizeRoutingValue(targetKind);
        if (values == null || normalizedTargetKind == null) {
            return;
        }
        values.put("targetKind", normalizedTargetKind);
        values.put("finalDecision", normalizedTargetKind);
        if (candidateContains(rawCandidates, normalizedTargetKind)) {
            return;
        }
        Double confidence = confidence(values.get("confidence"));
        if (confidence == null) {
            confidence = maxCandidateConfidence(rawCandidates);
        }
        if (confidence == null) {
            confidence = 0.9;
        }
        values.put("confidence", confidence);
        values.put("candidates", List.of(Map.of(
            "targetKind", normalizedTargetKind,
            "confidence", confidence
        )));
    }

    private boolean candidateContains(Object rawCandidates, String targetKind) {
        String normalizedTargetKind = normalizeRoutingValue(targetKind);
        if (!(rawCandidates instanceof List<?> candidates) || normalizedTargetKind == null) {
            return false;
        }
        for (Object item : candidates) {
            if (item instanceof Map<?, ?> candidate
                && normalizedTargetKind.equals(normalizeRoutingValue(firstText(candidate.get("targetKind"))))) {
                return true;
            }
        }
        return false;
    }

    private Double maxCandidateConfidence(Object rawCandidates) {
        if (!(rawCandidates instanceof List<?> candidates)) {
            return null;
        }
        Double max = null;
        for (Object item : candidates) {
            if (!(item instanceof Map<?, ?> candidate)) {
                continue;
            }
            Double value = confidence(candidate.get("confidence"));
            if (value != null && (max == null || value > max)) {
                max = value;
            }
        }
        return max;
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private Map<String, Object> denied(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>(values == null ? Map.of() : values);
        result.put(STATUS_KEY, "DENIED");
        result.put(ERROR_KEY, message);
        result.put(CODE_KEY, "MCP_PARAM_BINDING_DENIED");
        return result;
    }

    private Map<String, Object> reviewRequired(Map<String, Object> values, String message) {
        Map<String, Object> result = new LinkedHashMap<>(values == null ? Map.of() : values);
        result.put(STATUS_KEY, "REVIEW_REQUIRED");
        result.put(ERROR_KEY, message);
        result.put(CODE_KEY, "MCP_ROUTING_REVIEW_REQUIRED");
        result.put("routingDecision", Map.of(
            "decision", "REVIEW_REQUIRED",
            "threshold", TARGET_KIND_CONFIDENCE_THRESHOLD,
            "reason", message
        ));
        return result;
    }

    private boolean isMcpTool(String toolName, ToolMetadata metadata) {
        if (metadata != null) {
            if (ToolWorkflowContract.isDeclared(metadata)
                && ToolWorkflowContract.declaredProtocolFamily(metadata)
                    .map(family -> family.toLowerCase(Locale.ROOT).startsWith("mcp."))
                    .orElse(false)) {
                return true;
            }
            if (metadata.getCategories() != null && metadata.getCategories().stream()
                .map(value -> value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT))
                .anyMatch(MCP_CATEGORIES::contains)) {
                return true;
            }
            if (metadata.getMetadata() != null && metadata.getMetadata().containsKey("remoteToolName")) {
                return true;
            }
        }
        return toolName != null && toolName.startsWith("mcp_");
    }

    private String removeTargetKind(Map<String, Object> values, McpArgumentBindingFieldPolicy fields) {
        if (values == null) {
            return null;
        }
        for (String field : fields.targetKindFields()) {
            Object value = values.remove(field);
            if (hasText(value)) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private Double confidence(Object value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private Double candidateConfidence(Object rawCandidates, String targetKind) {
        String normalizedTargetKind = normalizeRoutingValue(targetKind);
        if (normalizedTargetKind == null || !(rawCandidates instanceof List<?> candidates)) {
            return null;
        }
        for (Object item : candidates) {
            Map<String, Object> candidate = asMap(item);
            if (normalizedTargetKind.equals(normalizeRoutingValue(firstText(candidate.get("targetKind"))))) {
                return confidence(candidate.get("confidence"));
            }
        }
        return null;
    }

    private void renameFirst(Map<String, Object> values, String target, String... aliases) {
        if (hasText(values.get(target))) {
            return;
        }
        for (String alias : aliases) {
            Object value = values.remove(alias);
            if (hasText(value)) {
                values.put(target, value);
                return;
            }
        }
    }

    private void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String targetKey, String... aliases) {
        Object direct = source.remove(targetKey);
        if (hasText(direct)) {
            target.put(targetKey, direct);
            return;
        }
        for (String alias : aliases) {
            Object value = source.remove(alias);
            if (hasText(value)) {
                target.put(targetKey, value);
                return;
            }
        }
    }

    private String firstPresentField(Map<String, Object> values, List<String> fields) {
        if (values == null) {
            return null;
        }
        for (String field : fields) {
            if (hasText(values.get(field))) {
                return field;
            }
        }
        Object context = firstPresent(values, "executionContext", "mcpExecutionContext", "filters");
        if (context instanceof Map<?, ?> map) {
            for (String field : fields) {
                if (hasText(map.get(field))) {
                    return field;
                }
            }
        }
        return null;
    }

    private void removeForbidden(Map<String, Object> values, McpArgumentBindingFieldPolicy fields) {
        if (values == null) {
            return;
        }
        fields.concreteTargetFields().forEach(values::remove);
        fields.rawExecutionFields().forEach(values::remove);
        fields.filterProtocolFields().forEach(values::remove);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object value) {
        return hasText(value) ? String.valueOf(value).trim() : null;
    }

    private static String normalizeRoutingValue(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(Object value) {
        if (!hasText(value)) {
            return null;
        }
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isBlank();
    }

    private record IntentCandidate(List<String> terms, double score) {
    }

    private record PublishedDiscoveryContract(boolean published,
                                               Set<String> acceptedFields,
                                               Set<String> requiredFields,
                                               String forcedTargetKind,
                                               String forcedAssetType,
                                               Map<String, String> targetKindToAssetType,
                                               Set<String> allowedTargetKinds,
                                               boolean requiresRoutingDecision) {
        private static PublishedDiscoveryContract legacy() {
            return new PublishedDiscoveryContract(false, Set.of(), Set.of(), null, null,
                Map.of(), Set.of(), true);
        }

        private String assetTypeFor(String targetKind) {
            String normalizedKind = normalizeRoutingValue(targetKind);
            if (normalizedKind == null) return null;
            if (!allowedTargetKinds.isEmpty() && !allowedTargetKinds.contains(normalizedKind)) return null;
            if (normalizedKind.equals(normalizeRoutingValue(forcedTargetKind))
                && forcedAssetType != null && !forcedAssetType.isBlank()) {
                return normalizeRoutingValue(forcedAssetType);
            }
            return targetKindToAssetType.get(normalizedKind);
        }

        private String allowedKindsDescription() {
            if (!allowedTargetKinds.isEmpty()) return String.join(", ", allowedTargetKinds.stream().sorted().toList());
            if (forcedTargetKind != null && !forcedTargetKind.isBlank()) return forcedTargetKind;
            if (!targetKindToAssetType.isEmpty()) {
                return String.join(", ", targetKindToAssetType.keySet().stream().sorted().toList());
            }
            return "none published by the MCP tool";
        }

        private boolean accepts(String field) {
            return !published || acceptedFields.contains(canonical(field));
        }

        @SuppressWarnings("unused")
        private boolean requires(String field) {
            return requiredFields.contains(canonical(field));
        }

        private static String canonical(String field) {
            return field == null ? "" : field.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
    }
}
