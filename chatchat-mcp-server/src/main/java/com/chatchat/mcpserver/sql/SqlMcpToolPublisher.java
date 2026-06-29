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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final DataEngineQueryPlannerService queryPlannerService;
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
        remove("sql_query_plan");
        datasourceConfigService.listAll().forEach(datasource -> remove(datasource.getToolName()));
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();
        mcpSyncServer.addTool(sqlQueryPlanTool());
        mcpSyncServer.addTool(sqlQueryGatewayTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("SQL MCP gateway tools refreshed: sql_query_plan, sql_query_execute");
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

    private McpServerFeatures.SyncToolSpecification sqlQueryPlanTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_query_plan")
            .title("MCP Data Engine query planner")
            .description("Build a read-only MCP Data Engine v4 query plan for a logical SQL datasource. "
                + "This tool does not execute SQL. It returns a QueryPlan DAG, join graph, cost model, and resolved table candidates. "
                + "Use it before sql_query_execute for multi-table, trend, metric, or ambiguous schema analysis.")
            .inputSchema(planInputSchema())
            .meta(planMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "sql_query_plan",
                "sql",
                request.arguments(),
                () -> {
                    QueryPlan plan = queryPlannerService.plan(executionTargetRouter.routeSqlQuery(request.arguments()));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("SQL query plan completed")
                        .structuredContent(plan.toDiagnostic())
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

    private McpSchema.JsonSchema planInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "question", Map.of("type", "string", "description", "Natural-language analytical question or query intent"),
            "tables", Map.of(
                "type", "array",
                "description", "Optional logical table names mentioned by the user; the planner will resolve schemas and variants",
                "items", Map.of("type", "string")
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as env, assetName, databaseType, service, target, or labels"
            ),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20)
        ), List.of("question", "executionContext"), false, null, null);
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

    private Map<String, Object> planMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "sql_planning");
        governance.put("operation_type", "read_only_plan");
        governance.put("risk_level", "low");
        governance.put("data_scope", "database:metadata");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "none", "allow_user_override", false));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("sql_gateway", "sql_query_plan", governance, null));
        meta.put("runtime_action", "allow");
        meta.put("runtimeAction", "allow");
        meta.put("plannerVersion", "mcp_data_engine_v4");
        meta.put("targetRoutingRequired", true);
        meta.put("outputSchema", "query_plan.v4");
        meta.put("doesNotExecuteSql", true);
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("sql_query_plan", "sql"));
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
        return mutableMap(
            "templateId", template.getCode(),
            "name", firstText(template.getTitle(), template.getCode()),
            "description", firstText(template.getDescription(), ""),
            "category", firstText(template.getCategory(), "sql_diagnostic"),
            "riskLevel", firstText(template.getRiskLevel(), "MEDIUM"),
            "databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            "binding", mutableMap("datasourceId", blankToNull(template.getDatasourceId())),
            "intentSignals", readStringList(template.getIntentSignalsJson()),
            "parameterSchema", readJsonObject(template.getParameterSchemaJson()),
            "rawExecutionSpecReturned", false
        );
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
            "selectionFields", List.of("templateId", "name", "description", "databaseType", "intentSignals", "parameterSchema"),
            "mustUseDiscoveredTemplate", true,
            "metadataWorkflow", "For table structure analysis, discover schemas/tables first (for example MYSQL_SCHEMA_TABLE_OVERVIEW or MYSQL_TABLE_LOCATION), then call the table metadata template with the resolved schemaName.",
            "onNoMatch", "call sql_datasource_template_query with executionContext; if no authorized template is returned, either use explicit read-only sql when policy permits or explain that no existing authorized template can satisfy the request",
            "doNotInventTemplateNames", true,
            "rawSqlTemplateReturned", false
        );
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
