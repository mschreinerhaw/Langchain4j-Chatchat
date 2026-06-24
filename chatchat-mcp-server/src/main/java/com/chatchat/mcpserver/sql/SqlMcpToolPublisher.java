package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
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
    private final SqlQueryExecuteService executeService;
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
        datasourceConfigService.listAll().forEach(datasource -> remove(datasource.getToolName()));
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();
        mcpSyncServer.addTool(sqlQueryGatewayTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("SQL MCP gateway tool refreshed: sql_query_execute");
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
                + "Do not pass datasourceId, JDBC URL, or any concrete database endpoint.")
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

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "只读 SQL。禁止多语句和注释。"),
            "template", Map.of("type", "string", "description", "SQL 模板编号，例如 CHECK_TABLE_COUNT、CHECK_RECENT_DATA"),
            "parameters", Map.of("type", "object", "additionalProperties", true),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 60),
            "maxRows", Map.of("type", "integer", "minimum", 1, "maximum", 5000),
            "purpose", Map.of("type", "string", "description", "查询目的，必须展示给用户确认并写入审计"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of(), false, null, null);
    }

    private McpSchema.JsonSchema gatewayInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "Read-only SQL. Multi-statement, comments, writes, DDL, and permission changes are forbidden."),
            "template", Map.of("type", "string", "description", "SQL template id, for example CHECK_TABLE_COUNT or CHECK_RECENT_DATA"),
            "parameters", Map.of("type", "object", "additionalProperties", true),
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
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("assetMetadata", assetMetadataFactory.gateway(
            "sql_datasource",
            datasourceConfigService.listEnabled().stream().map(assetMetadataFactory::sqlDatasource).toList(),
            List.of("datasourceId", "jdbcUrl", "url", "connectionString")
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("sql_query_execute", "sql"));
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
