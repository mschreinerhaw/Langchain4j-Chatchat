package com.chatchat.mcpserver.metadata.tool;

import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataMatchingService;
import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataRequestAdapter;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataSearchService;
import com.chatchat.mcpserver.metadata.search.workflow.EnterpriseMetadataSearchWorkflow;
import com.chatchat.mcpserver.metadata.governance.MetadataGovernancePolicyService;

import com.chatchat.mcpserver.mcp.McpToolApplicability;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class EnterpriseMetadataMcpToolPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {

    public static final String TOOL_NAME = "enterprise_metadata_search";
    public static final String RETIRED_MATCH_TOOL_NAME = "enterprise_metadata_match";
    private static final int MAX_DISCOVERY_QUERY_CHARS = 512;
    private static final int MAX_DISCOVERY_TERMS = 120;
    private static final int MAX_DISCOVERY_TERM_CHARS = 128;
    private final McpSyncServer mcpSyncServer;
    private final EnterpriseMetadataSearchWorkflow workflow;
    private final EnterpriseMetadataProperties properties;
    private final MetadataGovernancePolicyService policyService;

    @Autowired
    public EnterpriseMetadataMcpToolPublisher(McpSyncServer mcpSyncServer,
                                              EnterpriseMetadataSearchWorkflow workflow,
                                              EnterpriseMetadataProperties properties,
                                              MetadataGovernancePolicyService policyService) {
        this.mcpSyncServer = mcpSyncServer;
        this.workflow = workflow;
        this.properties = properties;
        this.policyService = policyService;
    }

    /** Compatibility constructor used by isolated tests and non-Spring embeddings. */
    public EnterpriseMetadataMcpToolPublisher(McpSyncServer mcpSyncServer,
                                              EnterpriseMetadataMatchingService matchingService,
                                              EnterpriseMetadataSearchService searchService,
                                              EnterpriseMetadataRequestAdapter requestAdapter,
                                              EnterpriseMetadataProperties properties,
                                              MetadataGovernancePolicyService policyService) {
        this(mcpSyncServer, new EnterpriseMetadataSearchWorkflow(
            matchingService, searchService, requestAdapter), properties, policyService);
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    public synchronized void refresh() {
        refreshPublication();
        log.info("Enterprise metadata MCP capabilities registered tools={}; retiredToolRemoved={}",
            TOOL_NAME, RETIRED_MATCH_TOOL_NAME);
    }

    @Override public String contributorId() { return "enterprise_metadata"; }
    @Override public McpSyncServer publicationServer() { return mcpSyncServer; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return properties.isEnabled()
            ? List.of(com.chatchat.mcpserver.tool.ToolPublication.from(searchSpecification())) : List.of();
    }
    @Override public Set<String> retiredToolNames() { return Set.of(RETIRED_MATCH_TOOL_NAME); }

    private McpServerFeatures.SyncToolSpecification searchSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("Enterprise metadata search")
            .description("Search configured enterprise standard fields, business roots and code dictionaries. "
                + "Every invocation performs the required standard-field, term-root and dictionary retrieval internally; "
                + "For a new table whose fields do not exist yet, supply queryTerms (or query) containing model-extracted "
                + "business concepts and candidate field meanings; each requested concept returns at most one qualified metadata record. "
                + "When fields are supplied, one invocation internally evaluates standard fields, term roots and dictionaries "
                + "for every field, then returns at most one qualified field-scoped metadata decision. Do not split those "
                + "metadata types into separate tool calls. "
                + "For CREATE TABLE requests, use queryTerms for discovery when the draft schema is not yet known; when a "
                + "complete model-proposed schema exists, place it in fields and the proposed table name in targetObject. "
                + "A downstream reasoning/script step must review the returned evidence before producing DDL. "
                + "Use this read-only capability when a task needs enterprise field meaning, technical names, "
                + "data types, standard definitions or business-term mapping. It does not create tables, "
                + "generate SQL or execute a downstream business workflow. The returned evidenceCoverage describes which field-standard reference data "
                + "was returned; it does not decide whether the user's broader design conclusion is true or false. "
                + "Read evidenceBundle first: it separates target facts, enterprise-standard references, and model inference guidance. "
                + "Treat results and evidenceObjects as retrieval provenance; "
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
                        "schemaVersion", EnterpriseMetadataSearchService.CARDINALITY_SCHEMA_VERSION,
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
        return workflow.execute(arguments == null ? Map.of() : arguments);
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
                "maxLength", MAX_DISCOVERY_QUERY_CHARS,
                "description", "Short overall retrieval context only. For multiple independently matched fields or concepts, use queryTerms. Never include explanations, tool results or final-answer prose."
            ),
            "queryTerms", mapOf(
                "type", "array",
                "maxItems", MAX_DISCOVERY_TERMS,
                "items", Map.of("type", "string", "maxLength", MAX_DISCOVERY_TERM_CHARS),
                "aliases", List.of("keywords", "keyword", "queries"),
                "acceptedSources", List.of("keywords", "keyword", "queries"),
                "description", "Independent model-extracted business concepts and candidate field meanings. Each array item is one retrieval requirement and returns at most one qualified record."
            ),
            "searchTerms", mapOf(
                "type", "array",
                "maxItems", MAX_DISCOVERY_TERMS,
                "items", Map.of("type", "string", "maxLength", MAX_DISCOVERY_TERM_CHARS),
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
                "description", "Legacy per-term candidate hint. It never truncates queryTerms; every queryTerms item is evaluated independently"
            ),
            "candidateLimitPerType", mapOf(
                "type", "integer",
                "minimum", 1,
                "description", "Internal recall-pool size per metadata type. Expanded candidates are quality-reranked and are not returned to the reasoning layer"
            )
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", EnterpriseMetadataSearchService.CARDINALITY_SCHEMA_VERSION,
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
                "reasoningBundlePath", "evidenceBundle",
                "reasoningBundleVersion", "enterprise_metadata_evidence_bundle.v1",
                "types", policyService.current().getMetadataContract().getRequiredBundle(),
                "factBoundary", "returned_records_only",
                "requiredRetrieval", String.join("+",
                    policyService.current().getMetadataContract().getRequiredBundle()),
                "allTypesAttemptedPath", "requirementMatches[].allMetadataTypesAttempted",
                "qualityDecisionPath", "requirementMatches[].selectionStatus",
                "cardinalityPreservedPath", "cardinalityPreserved"
            ),
            "cardinalityContract", mapOf(
                "policy", "ONE_OR_ZERO_PER_REQUIREMENT",
                "requirementPath", "queryTerms[] or fields[]",
                "matchPath", "requirementMatches[] or fieldMatches[]",
                "returnedCountPath", "returnedMetadataCount",
                "candidateExpansion", "internal_only"
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

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
