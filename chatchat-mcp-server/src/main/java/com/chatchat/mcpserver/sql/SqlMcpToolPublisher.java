package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqlMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlTemplateService sqlTemplateService;
    private final SqlQueryExecuteService executeService;
    private final SqlMetadataSearchService metadataSearchService;
    private final ExecutionTargetRouter executionTargetRouter;
    private final AssetMetadataFactory assetMetadataFactory;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final StandardToolExecutionResultFactory standardResultFactory;
    private final ObjectMapper objectMapper;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove("sql_query_execute");
        remove("sql_metadata_search");
        datasourceConfigService.listAll().forEach(datasource -> remove(datasource.getToolName()));
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();
        mcpSyncServer.addTool(sqlMetadataSearchTool());
        mcpSyncServer.addTool(sqlQueryGatewayTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("SQL MCP gateway tools refreshed: sql_metadata_search, sql_query_execute");
    }

    private McpServerFeatures.SyncToolSpecification toToolSpecification(SqlDatasourceConfig datasource) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(datasource.getToolName())
            .title(datasource.getTitle())
            .description(description(datasource))
            .inputSchema(inputSchema())
            .meta(meta(datasource))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                datasource.getToolName(),
                "sql",
                request.arguments(),
                () -> toCallToolResult(executeService.execute(datasource, request.arguments()))))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification sqlQueryGatewayTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_query_execute")
            .title("SQL query execution gateway")
            .description("Execute a read-only SQL query or SQL template on a routed logical datasource target. "
                + "When using template, the value must be an existing templateId returned by sql_datasource_template_query for the same logical datasource. "
                + "For table metadata analysis, locate the table schema with metadata discovery templates such as MYSQL_SCHEMA_TABLE_OVERVIEW or MYSQL_TABLE_LOCATION before reading columns. "
                + "Do not invent template names and do not pass datasourceId, JDBC URL, or any concrete database endpoint.")
            .inputSchema(gatewayInputSchema())
            .meta(gatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "sql_query_execute",
                "sql",
                request.arguments(),
                () -> toCallToolResult(executeService.execute(
                    executionTargetRouter.routeSqlQuery(request.arguments())))))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification sqlMetadataSearchTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_metadata_search")
            .title("SQL metadata table search")
            .description("Read-only Lucene-backed metadata retrieval tool for locating datasource/database/table entries before SQL template execution. "
                + "Use this before sql_datasource_template_query and sql_query_execute when the user mentions a table name, table comment, business meaning, or when schema/database is unknown. "
                + "When an explicit tableName/schema.table is provided, it returns cached column metadata including column name, type, key, nullability, ordinal position, and comment. "
                + "It returns logical routing context and table locations from the metadata index; it never returns JDBC URLs or raw SQL.")
            .inputSchema(metadataSearchInputSchema())
            .meta(metadataSearchMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "sql_metadata_search",
                "sql",
                request.arguments(),
                () -> {
                    Map<String, Object> result = metadataSearchService.search(request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("SQL metadata search completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                }))
            .build();
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "只读 SQL。禁止多语句和注释。"),
            "template", Map.of("type", "string", "description", "Existing SQL templateId from this datasource asset's authorizedSqlTemplates or sql_datasource_template_query result. Do not invent names."),
            "parameters", Map.of(
                "type", "object",
                "description", "Template parameters object. Use exactly the fields required by sql_datasource_template_query.templates[].parameterSchema; do not put template parameters at the top level.",
                "additionalProperties", true
            ),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 60),
            "maxRows", Map.of("type", "integer", "minimum", 1, "maximum", 5000),
            "purpose", Map.of("type", "string", "description", "查询目的，必须展示给用户确认并写入审计"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of(), false, null, null);
    }

    private McpSchema.JsonSchema gatewayInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "Read-only SQL. Multi-statement, comments, writes, DDL, and permission changes are forbidden."),
            "template", Map.of("type", "string", "description", "Existing SQL templateId from sql_datasource_template_query.templates[].templateId for the selected datasource. Do not invent names."),
            "parameters", Map.of(
                "type", "object",
                "description", "Template parameters object. Use exactly the fields required by sql_datasource_template_query.templates[].parameterSchema; do not put template parameters at the top level.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as env, cluster, database, databaseRole, targetType, service, or labels"
            ),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 60),
            "maxRows", Map.of("type", "integer", "minimum", 1, "maximum", 5000),
            "purpose", Map.of("type", "string", "description", "Query purpose for confirmation and audit"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of("executionContext"), false, null, null);
    }

    private McpSchema.JsonSchema metadataSearchInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "query", Map.of("type", "string", "description", "Free-text table/database search query, for example a table name, table comment/business meaning, database description, schema.table, or asset.database.table path."),
            "tableName", Map.of("type", "string", "description", "Optional explicit table name from the user request."),
            "database", Map.of("type", "string", "description", "Optional database/schema name filter."),
            "schema", Map.of("type", "string", "description", "Alias of database."),
            "assetName", Map.of("type", "string", "description", "Optional logical datasource asset name, never a datasourceId."),
            "executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as assetName, env, databaseType, database/schema, or tableName. Concrete target fields are forbidden."
            ),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 30),
            "includeColumns", Map.of("type", "boolean", "description", "Optional; true includes cached column metadata for matched tables. Defaults to true; set false only for lightweight routing lookup.")
        ), List.of(), false, null, null);
    }

    private Map<String, Object> meta(SqlDatasourceConfig datasource) {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "sql_query");
        governance.put("operation_type", "read_sql");
        governance.put("risk_level", "high");
        governance.put("data_scope", "database:" + datasource.getName());
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("sql", "template", "parameters", "timeoutSeconds", "maxRows", "purpose", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("sql_datasource", datasource.getId(), governance, datasource.getGovernanceJson()));
        meta.put("runtime_action", datasource.getRuntimeAction());
        meta.put("runtimeAction", datasource.getRuntimeAction());
        meta.put("datasourceId", datasource.getId());
        meta.put("datasourceName", datasource.getName());
        meta.put("environment", datasource.getEnvironment());
        meta.put("allowedStatements", List.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"));
        meta.put("templateRegistrySupported", true);
        meta.put("authorizedSqlTemplates", authorizedSqlTemplates(datasource));
        meta.put("templateSelectionPolicy", templateSelectionPolicy());
        meta.put("assetMetadata", assetMetadataFactory.sqlDatasource(datasource));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta(datasource.getToolName(), "sql"));
        return meta;
    }

    private Map<String, Object> gatewayMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "sql_gateway");
        governance.put("operation_type", "read_sql");
        governance.put("risk_level", "high");
        governance.put("data_scope", "database:routed");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("sql", "template", "parameters", "executionContext", "timeoutSeconds", "maxRows", "purpose", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("sql_gateway", "sql_query_execute", governance, null));
        meta.put("runtime_action", "confirm_required");
        meta.put("runtimeAction", "confirm_required");
        meta.put("allowedStatements", List.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"));
        meta.put("templateRegistrySupported", true);
        meta.put("targetRoutingRequired", true);
        meta.put("authorizedSqlTemplatesByAsset", authorizedSqlTemplatesByAsset());
        meta.put("templateSelectionPolicy", templateSelectionPolicy());
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("assetMetadata", assetMetadataFactory.gateway(
            "sql_datasource",
            datasourceConfigService.listEnabled().stream().map(assetMetadataFactory::sqlDatasource).toList(),
            List.of("datasourceId", "jdbcUrl", "url", "connectionString")
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("sql_query_execute", "sql"));
        return meta;
    }

    private Map<String, Object> metadataSearchMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "sql_metadata_search");
        governance.put("operation_type", "read_metadata_index");
        governance.put("risk_level", "low");
        governance.put("data_scope", "database:metadata");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "none", "allow_user_override", false));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("sql_gateway", "sql_metadata_search", governance, null));
        meta.put("runtime_action", "allow");
        meta.put("runtimeAction", "allow");
        meta.put("outputSchema", SqlMetadataSearchService.RESULT_SCHEMA_VERSION);
        meta.put("doesNotExecuteSql", true);
        meta.put("indexBackend", "lucene_metadata_table_index");
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("resultShape", mutableMap(
            "tableLocationPath", "results[].location",
            "executionContextPath", "results[].sqlExecutionBinding.executionContext",
            "templateParameterPath", "results[].sqlExecutionBinding.parameters",
            "nextTool", "sql_datasource_template_query then sql_query_execute"
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("sql_metadata_search", "sql"));
        return meta;
    }

    private String description(SqlDatasourceConfig datasource) {
        if (datasource.getDescription() != null && !datasource.getDescription().isBlank()) {
            return datasource.getDescription();
        }
        return "用途：查询 " + datasource.getName()
            + "，只允许 SELECT/SHOW/DESCRIBE/EXPLAIN。禁止任何写入、DDL、权限、删除、更新操作。";
    }

    private McpSchema.CallToolResult toCallToolResult(SqlQueryResult result) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(result.success() ? "SQL query completed" : result.errorMessage())
            .structuredContent(standardResultFactory.fromSql(result))
            .isError(!result.success())
            .build();
    }

    private List<Map<String, Object>> authorizedSqlTemplatesByAsset() {
        return datasourceConfigService.listEnabled().stream()
            .map(datasource -> mutableMap(
                "assetId", datasource.getId(),
                "assetName", datasource.getName(),
                "toolName", datasource.getToolName(),
                "environment", datasource.getEnvironment(),
                "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()),
                "templates", authorizedSqlTemplates(datasource)
            ))
            .toList();
    }

    private List<Map<String, Object>> authorizedSqlTemplates(SqlDatasourceConfig datasource) {
        List<String> allowed = expandedAllowedTemplateCodes(datasource);
        String datasourceType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
        return sqlTemplateService.listEnabled().stream()
            .filter(template -> allowed.isEmpty() || allowed.contains(normalizeCode(template.getCode())))
            .filter(template -> compatibleTemplate(template, datasource, datasourceType))
            .map(this::templateSummary)
            .toList();
    }

    private List<String> expandedAllowedTemplateCodes(SqlDatasourceConfig datasource) {
        List<String> allowed = allowedTemplateCodes(datasource);
        if (allowed.isEmpty()) {
            return allowed;
        }
        LinkedHashSet<String> values = new LinkedHashSet<>(allowed);
        if (values.contains("MYSQL_TABLE_METADATA")) {
            values.add("MYSQL_SCHEMA_TABLE_OVERVIEW");
            values.add("MYSQL_TABLE_LOCATION");
        }
        return values.stream().toList();
    }

    private boolean compatibleTemplate(SqlTemplateConfig template, SqlDatasourceConfig datasource, String datasourceType) {
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        if (!"generic".equals(templateType) && !templateType.equals(datasourceType)) {
            return false;
        }
        String boundDatasourceId = blankToNull(template.getDatasourceId());
        return boundDatasourceId == null || boundDatasourceId.equals(datasource.getId());
    }

    private Map<String, Object> templateSummary(SqlTemplateConfig template) {
        Map<String, Object> parameterSchema = readJsonObject(template.getParameterSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mutableMap(
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "category", firstText(template.getCategory(), "sql_diagnostic"),
            "riskLevel", firstText(template.getRiskLevel(), "MEDIUM"),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            "semantic", sqlTemplateSemanticMetadata(template),
            "binding", mutableMap("datasourceId", blankToNull(template.getDatasourceId())),
            "intentSignals", readStringList(template.getIntentSignalsJson()),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(template.getCode(), parameterSchema),
            "invocationExample", invocationExample(template.getCode(), parameterSchema),
            "rawExecutionSpecReturned", false
        );
    }

    private Map<String, Object> sqlTemplateSemanticMetadata(SqlTemplateConfig template) {
        String databaseType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        String category = firstText(template.getCategory(), "sql_diagnostic");
        Map<String, Object> parameterSchema = readJsonObject(template.getParameterSchemaJson());
        boolean requiresTable = parameterRequired(parameterSchema, "tableName", "table_name");
        List<String> signals = readStringList(template.getIntentSignalsJson());
        boolean metadataIntent = containsIgnoreCase(category, "metadata") || containsAnyIgnoreCase(signals, "metadata", "schema", "column", "describe");
        String targetLevel = requiresTable ? "TABLE" : targetLevelFromCategory(category);
        String operation = requiresTable && metadataIntent ? "TABLE_METADATA_QUERY" : operationFromCategory(category);
        return mutableMap(
            "schemaVersion", "sql_template_semantic.v1",
            "operation", operation,
            "targetLevel", targetLevel,
            "dialect", databaseType,
            "dialects", List.of(databaseType),
            "requiresTableName", requiresTable,
            "scope", operation
        );
    }

    private boolean parameterRequired(Map<String, Object> schema, String... names) {
        Object required = schema == null ? null : schema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return false;
        }
        Set<String> expected = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) {
                expected.add(name.replace("_", "").toLowerCase(Locale.ROOT));
            }
        }
        for (Object item : iterable) {
            if (item != null && expected.contains(String.valueOf(item).replace("_", "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String targetLevelFromCategory(String category) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (normalized.contains("schema") || normalized.contains("storage")) {
            return "SCHEMA";
        }
        return "INSTANCE";
    }

    private String operationFromCategory(String category) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (normalized.contains("lock")) {
            return "LOCK_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("connection") || normalized.contains("session")) {
            return "SESSION_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("storage")) {
            return "STORAGE_DIAGNOSTIC_QUERY";
        }
        if (normalized.contains("metadata")) {
            return "METADATA_QUERY";
        }
        return "INSTANCE_DIAGNOSTIC_QUERY";
    }

    private boolean containsAnyIgnoreCase(List<String> values, String... needles) {
        if (values == null || values.isEmpty() || needles == null) {
            return false;
        }
        for (String value : values) {
            for (String needle : needles) {
                if (containsIgnoreCase(value, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
            && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private List<String> allowedTemplateCodes(SqlDatasourceConfig datasource) {
        String json = datasource.getAllowedTemplatesJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::normalizeCode)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> templateSelectionPolicy() {
        return mutableMap(
            "source", "sql_datasource_template_query.templates[].templateId",
            "allowedSet", "authorizedSqlTemplates[].templateId or authorizedSqlTemplatesByAsset[].templates[].templateId",
            "selectionFields", List.of("templateId", "name", "description", "databaseType", "intentSignals",
                "parameterSchema", "requiredParameters", "parameterContract", "invocationExample"),
            "mustUseDiscoveredTemplate", true,
            "metadataWorkflow", "For table structure analysis, discover schemas/tables first (for example MYSQL_SCHEMA_TABLE_OVERVIEW or MYSQL_TABLE_LOCATION), then call the table metadata template with the resolved schemaName.",
            "onNoMatch", "call sql_datasource_template_query with executionContext; if no authorized template is returned, either use explicit read-only sql when policy permits or explain that no existing authorized template can satisfy the request",
            "doNotInventTemplateNames", true,
            "rawSqlTemplateReturned", false
        );
    }

    private List<String> requiredParameters(Map<String, Object> parameterSchema) {
        Object required = parameterSchema == null ? null : parameterSchema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterContract(String templateId, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        List<String> required = requiredParameters(parameterSchema);
        return mutableMap(
            "schemaVersion", "template_parameter_contract.v1",
            "templateId", templateId,
            "argumentContainer", "sql_query_execute.parameters",
            "required", required,
            "optional", properties.keySet().stream()
                .filter(key -> !required.contains(key))
                .toList(),
            "mustPassUnderParameters", true,
            "topLevelTemplateParametersAllowed", false,
            "missingRequiredBehavior", "Do not call sql_query_execute until every required field is present under parameters."
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invocationExample(String templateId, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (String required : requiredParameters(parameterSchema)) {
            parameters.put(required, exampleValue(required, properties.get(required)));
        }
        return mutableMap(
            "tool", "sql_query_execute",
            "templateId", templateId,
            "parameters", parameters,
            "executionContext", mutableMap("assetName", "<assetName from sql_datasource_asset_query>", "env", "<env>")
        );
    }

    @SuppressWarnings("unchecked")
    private String exampleValue(String name, Object schema) {
        String normalized = name == null ? "" : name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("tablename")) {
            return "<tableName from user request>";
        }
        if (normalized.contains("schemaname") || normalized.contains("databasename") || "schema".equals(normalized)
            || "database".equals(normalized)) {
            return "<schemaName resolved by table-location query>";
        }
        if (schema instanceof Map<?, ?> map && map.get("example") != null) {
            return String.valueOf(map.get("example"));
        }
        return "<" + name + ">";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception ignored) {
            // Fall through to stable empty schema.
        }
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("SQL MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
