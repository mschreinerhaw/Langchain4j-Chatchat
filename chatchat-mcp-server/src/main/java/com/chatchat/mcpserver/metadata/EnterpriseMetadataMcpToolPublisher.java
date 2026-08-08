package com.chatchat.mcpserver.metadata;

import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnterpriseMetadataMcpToolPublisher {

    public static final String TOOL_NAME = "enterprise_metadata_search";
    public static final String RETIRED_MATCH_TOOL_NAME = "enterprise_metadata_match";

    private final McpSyncServer mcpSyncServer;
    private final EnterpriseMetadataMatchingService matchingService;
    private final EnterpriseMetadataSearchService searchService;
    private final EnterpriseMetadataRequestAdapter requestAdapter;
    private final EnterpriseMetadataProperties properties;
    private final MetadataGovernancePolicyService policyService;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled()) {
            refresh();
        }
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        remove(RETIRED_MATCH_TOOL_NAME);
        com.chatchat.mcpserver.tool.McpToolPublicationReviewer.addReviewedTool(
            mcpSyncServer, searchSpecification());
        mcpSyncServer.notifyToolsListChanged();
        log.info("Enterprise metadata MCP capabilities registered tools={}; retiredToolRemoved={}",
            TOOL_NAME, RETIRED_MATCH_TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification searchSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Enterprise metadata search")
            .description("Search configured enterprise standard fields, business roots and code dictionaries. "
                + "Every invocation performs the required standard-field, term-root and dictionary retrieval internally; "
                + "For a new table whose fields do not exist yet, supply queryTerms (or query) containing model-extracted "
                + "business concepts and candidate field meanings; the tool returns relevant enterprise metadata records. "
                + "When fields are supplied, one invocation validates every supplied field and returns field-scoped "
                + "standard-field, term-root and dictionary evidence. Do not split those metadata types into separate tool calls. "
                + "For CREATE TABLE requests, use queryTerms for discovery when the draft schema is not yet known; when a "
                + "complete model-proposed schema exists, place it in fields and the proposed table name in targetObject. "
                + "A downstream reasoning/script step must review the returned evidence before producing DDL. "
                + "Use this read-only capability when a task needs enterprise field meaning, technical names, "
                + "data types, standard definitions or business-term mapping. It does not create tables, "
                + "generate SQL or execute a workflow. The returned claimCoverage is the authoritative, governance-policy-driven "
                + "declaration of supported and unassessed claims; callers must not assume a fixed table-design scope from the tool name. "
                + "Treat results and evidenceObjects as the factual boundary; "
                + "never invent fields that were not returned.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    Map<String, Object> result = executeSearch(arguments);
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(matchSummary(result))
                        .structuredContent(result)
                        .isError(Boolean.FALSE.equals(result.get("success")))
                        .build();
                } catch (Exception ex) {
                    Map<String, Object> error = Map.of(
                        "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
                        "success", false,
                        "error", ex.getMessage()
                    );
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(error)
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    Map<String, Object> executeSearch(Map<String, Object> arguments) {
        Map<String, Object> normalizedArguments = normalizeSearchArguments(arguments);
        if (discoveryRequest(normalizedArguments)) {
            return executeDiscovery(normalizedArguments);
        }
        Map<String, Object> request = requestAdapter.adapt(normalizedArguments);
        List<Map<String, Object>> fields = maps(request.get("fields"));
        log.info("enterprise_metadata_search unified input requestId={} purpose={} fieldCount={} input={}",
            text(request.get("requestId")), text(request.get("purpose")),
            fields.size(), inputAudit(arguments, request, fields));
        if (fields.isEmpty()) {
            if (text(normalizedArguments.get("query")) != null) {
                return executeDiscovery(normalizedArguments);
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
        Map<String, Object> result = new LinkedHashMap<>(searchService.searchRequiredBundle(
            new EnterpriseMetadataSearchService.SearchRequest(
                text(arguments.get("query")),
                strings(firstPresent(arguments, "types", "metadataTypes")),
                strings(arguments.get("statuses")),
                strings(arguments.get("scenarios")),
                integerValue(firstPresent(arguments, "limit", "candidateLimit"))
            )
        ));
        result.put("invokedCapability", TOOL_NAME);
        result.put("operationMode", "ENTERPRISE_METADATA_DISCOVERY");
        result.put("inputTerms", strings(arguments.get("queryTerms")));
        log.info("enterprise_metadata_search discovery output requestId={} resultSummary={}",
            text(arguments.get("requestId")),
            ToolLogSummarizer.summarizeResult(TOOL_NAME, result));
        return Map.copyOf(result);
    }

    private Map<String, Object> normalizeSearchArguments(Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addText(terms, normalized.get("query"));
        addTexts(terms, normalized.get("queryTerms"));
        addTexts(terms, normalized.get("searchTerms"));
        addTexts(terms, normalized.get("keywords"));
        addText(terms, normalized.get("keyword"));
        addTexts(terms, normalized.get("queries"));
        if (!terms.isEmpty()) {
            normalized.put("query", String.join(" ", terms));
            normalized.put("queryTerms", List.copyOf(terms));
        }
        return normalized;
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

    private McpSchema.JsonSchema inputSchema() {
        Map<String, Object> fieldSchema = mapOf(
            "type", "object",
            "properties", mapOf(
                "fieldName", Map.of("type", "string"),
                "name", Map.of("type", "string"),
                "columnName", Map.of("type", "string"),
                "physicalName", Map.of("type", "string"),
                "enName", Map.of("type", "string"),
                "englishName", Map.of("type", "string"),
                "fieldCnName", Map.of("type", "string"),
                "cnName", Map.of("type", "string"),
                "chineseName", Map.of("type", "string"),
                "businessName", Map.of("type", "string"),
                "label", Map.of("type", "string"),
                "dataType", Map.of("type", "string"),
                "columnType", Map.of("type", "string"),
                "type", Map.of("type", "string"),
                "description", Map.of("type", "string"),
                "comment", Map.of("type", "string"),
                "remark", Map.of("type", "string"),
                "nullable", Map.of("type", "boolean"),
                "isNullable", Map.of("type", List.of("boolean", "string")),
                "defaultValue", Map.of("type", "string"),
                "default", Map.of("type", "string"),
                "businessDomain", Map.of("type", "string"),
                "domain", Map.of("type", "string")
            ),
            "additionalProperties", false
        );
        return new McpSchema.JsonSchema("object", mapOf(
            "query", Map.of(
                "type", "string",
                "description", "Unified retrieval expression assembled from the user request and prior structured evidence"
            ),
            "queryTerms", mapOf(
                "type", "array",
                "items", Map.of("type", "string"),
                "aliases", List.of("keywords", "keyword", "queries"),
                "acceptedSources", List.of("keywords", "keyword", "queries"),
                "description", "Model-extracted business concepts and candidate field meanings for new-table metadata discovery"
            ),
            "searchTerms", mapOf(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Compatibility alias for queryTerms"
            ),
            "purpose", Map.of(
                "type", "string",
                "description", "Optional review purpose for a structured field bundle"
            ),
            "requestId", Map.of(
                "type", "string",
                "description", "Optional caller correlation id"
            ),
            "matchMode", Map.of(
                "type", "string",
                "description", "Field matching mode such as FIELD_MAPPING"
            ),
            "matchStrategy", mapOf(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional field matching strategies"
            ),
            "targetObject", mapOf(
                "type", "object",
                "properties", mapOf(
                    "type", Map.of("type", "string"),
                    "name", Map.of("type", "string"),
                    "domain", Map.of("type", "string"),
                    "assetName", Map.of("type", "string"),
                    "database", Map.of("type", "string"),
                    "tableName", Map.of("type", "string")
                ),
                "additionalProperties", false
            ),
            "fields", mapOf(
                "type", "array",
                "items", fieldSchema,
                "description", "Fields to validate. Every supplied field is processed in this single invocation; "
                    + "the capability does not claim whole-table conformance."
            ),
            "tableName", Map.of(
                "type", "string",
                "description", "Proposed table name; converted to targetObject context when fields are supplied"
            ),
            "table", Map.of("type", "string"),
            "sourceEvidence", mapOf(
                "type", "array",
                "items", mapOf("type", "object", "additionalProperties", true),
                "description", "Runtime-transported outputs from declared dependency steps. "
                    + "Interpreted only by this capability's request adapter."
            ),
            "schemaEvidence", mapOf(
                "type", "object",
                "additionalProperties", true,
                "description", "Provenance for a model-assisted or deterministic field search projection"
            ),
            "modelSearchProfile", mapOf(
                "type", "object",
                "additionalProperties", true,
                "description", "Bounded audit metadata for the temporary model-assisted search profile"
            ),
            "types", Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum",
                    policyService.current().getMetadataContract().getRequiredBundle()),
                "description", "Optional type hints. The tool still performs the required standard-field, term-root and dictionary retrieval internally."
            ),
            "statuses", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional source status filters such as 标准、草案 or 启用"
            ),
            "scenarios", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional configured business scenario codes such as customer_account or risk_compliance"
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "description", "Caller-requested total metadata result count across standard fields, term roots and dictionaries; no fixed service cap is applied"
            ),
            "candidateLimitPerType", mapOf(
                "type", "integer",
                "minimum", 1,
                "description", "Caller-requested candidates per metadata type for every supplied field; defaults to limit when provided"
            )
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", EnterpriseMetadataSearchService.REQUIRED_BUNDLE_SCHEMA_VERSION,
            "kind", "enterprise_metadata_capability",
            "capabilityType", "metadata",
            "provider", "configured_catalog",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "readOnly", true,
            "riskLevel", "low",
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            McpToolApplicability.META_KEY, McpToolApplicability.of(
                "enterprise_metadata:search",
                "Enterprise data standard and business terminology retrieval",
                List.of("enterprise_metadata", "data_model", "sql_datasource"),
                "Retrieve enterprise-approved field, term and dictionary definitions as structured evidence.",
                List.of(
                    "Understand a business field before proposing schema or SQL",
                    "Find standard technical field names and data types",
                    "Map business terminology to enterprise roots or code dictionaries"
                ),
                List.of(
                    "Creating or altering a table",
                    "Executing SQL",
                    "Guessing fields not present in the returned evidence",
                    "Returning real data samples or sensitive field values",
                    "Proving complete table-level physical-design conformance"
                )
            ),
            "logicalIndexes", List.of(
                "enterprise_field_catalog",
                "enterprise_term_dictionary"
            ),
            "physicalIndex", properties.getIndexName(),
            "evidenceContract", mapOf(
                "resultPath", "evidenceObjects[]",
                "types", policyService.current().getMetadataContract().getRequiredBundle(),
                "factBoundary", "returned_records_only",
                "requiredRetrieval", String.join("+",
                    policyService.current().getMetadataContract().getRequiredBundle()),
                "allTypesAttemptedPath", "requiredRetrieval.allTypesAttempted",
                "evidenceCompletePath", "requiredRetrieval.evidenceComplete"
            ),
            "inputAdapterContract", mapOf(
                "contractVersion", "runtime_dependency_evidence.v1",
                "dependencyEvidenceParameter", "sourceEvidence",
                "dependencyScope", "declared_dependencies",
                "successOnly", true
            ),
            "modelInputBridgeContract", mapOf(
                "contractVersion", "model_assisted_retrieval.v1",
                "mode", "ENTERPRISE_METADATA_PROFILE",
                "allowedArgumentPaths", List.of(
                    "query", "queryTerms", "fields", "purpose", "schemaEvidence", "modelSearchProfile"
                ),
                "qualityGate", mapOf(
                    "enabled", true,
                    "minimumResultCount", 1,
                    "countPaths", List.of(
                        "count", "matchedFieldCount", "coverage.processedFieldCount",
                        "fieldMatches", "matches", "results"
                    )
                ),
                "guidance", "Combine query/queryTerms with declared SQL metadata evidence into a field-scoped verification profile."
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

    private String matchSummary(Map<String, Object> result) {
        if ("ENTERPRISE_METADATA_DISCOVERY".equals(result.get("operationMode"))) {
            return "Enterprise metadata discovery completed: records="
                + result.getOrDefault("count", 0)
                + ", backend=" + result.getOrDefault("backend", "unknown")
                + ". Review results, requiredRetrieval and evidenceObjects before designing fields.";
        }
        Map<String, Object> coverage = result.get("coverage") instanceof Map<?, ?> map
            ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue))
            : Map.of();
        return "Enterprise metadata field discovery completed: fields="
            + coverage.getOrDefault("processedFieldCount", 0)
            + ", allFieldsProcessed=" + coverage.getOrDefault("allFieldsProcessed", false)
            + ". Review fieldMatches and linked evidenceObjects before reuse.";
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

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Enterprise metadata MCP tool {} was not registered: {}",
                toolName, ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
