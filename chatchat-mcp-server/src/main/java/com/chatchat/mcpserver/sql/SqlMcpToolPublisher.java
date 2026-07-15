package com.chatchat.mcpserver.sql;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
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
    private final SqlScriptExecuteService scriptExecuteService;
    private final SqlMetadataSearchService metadataSearchService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final DatabaseQueryInvokeService databaseQueryInvokeService;
    private final ExecutionTargetRouter executionTargetRouter;
    private final AssetMetadataFactory assetMetadataFactory;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final StandardToolExecutionResultFactory standardResultFactory;
    private final ChatChatMcpServerProperties serverProperties;
    private final ObjectMapper objectMapper;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove("sql_query_execute");
        remove("sql_script_execute");
        remove("sql_metadata_search");
        datasourceConfigService.listAll().forEach(datasource -> remove(datasource.getToolName()));
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();
        mcpSyncServer.addTool(sqlMetadataSearchTool());
        mcpSyncServer.addTool(sqlQueryGatewayTool());
        mcpSyncServer.addTool(sqlScriptGatewayTool());
        mcpSyncServer.notifyToolsListChanged();
        log.info("SQL MCP gateway tools refreshed: sql_metadata_search, sql_query_execute, sql_script_execute");
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
                () -> executeDatasourceSql(datasource, request.arguments())))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification sqlQueryGatewayTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_query_execute")
            .title("SQL query execution gateway")
            .description("Execute a read-only SQL query or SQL template on a routed logical datasource target. "
                + "User SQL may contain comments; they are stripped before execution. If the sql field contains multiple read-only statements separated by semicolons, this gateway automatically executes it as a read-only SQL script and returns multiple result sets. "
                + "When using datasource maintenance template, the value must be an existing templateId returned by database_ops_template_search for the same logical datasource. "
                + "Business query templates are discovered with business_query_template_search; pass the returned executable template name as template/templateId to this executor with its executionContext. "
                + "For table metadata analysis, locate the table schema with metadata discovery templates such as MYSQL_SCHEMA_TABLE_OVERVIEW or MYSQL_TABLE_LOCATION before reading columns. "
                + "Do not invent template names and do not pass datasourceId, JDBC URL, or any concrete database endpoint.")
            .inputSchema(gatewayInputSchema())
            .meta(gatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "sql_query_execute",
                sqlGatewayRuntimeLevel(request.arguments()),
                request.arguments(),
                () -> executeSqlGateway(request.arguments())))
            .build();
    }

    private McpServerFeatures.SyncToolSpecification sqlScriptGatewayTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_script_execute")
            .title("SQL read-only script execution gateway")
            .description("Default model-facing executor for registered multi-SQL/DAG business query templates, or for a semicolon-separated read-only SQL analysis script, and return organized result sets. "
                + "For a template returned by business_query_template_search, pass its template/templateId, parameters, and executionContext; the configured dependency graph, parameter mappings, execution order, and result semantics are preserved. "
                + "Every statement must be SELECT, SHOW, DESCRIBE/DESC, or EXPLAIN. Writes, DDL, permissions, comments, stored procedures, SET/USE, and database admin operations are forbidden. "
                + "Use this when one SQL statement is not enough for business analysis and several independent result sets are needed. "
                + "Do not pass datasourceId, JDBC URL, or any concrete database endpoint.")
            .inputSchema(scriptGatewayInputSchema())
            .meta(scriptGatewayMeta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                "sql_script_execute",
                "sql_script",
                request.arguments(),
                () -> executeSqlScriptGateway(request.arguments())))
            .build();
    }

    private McpSchema.CallToolResult executeSqlScriptGateway(Map<String, Object> arguments) {
        DatabaseQueryConfig databaseQuery = businessDatabaseQueryTemplate(arguments);
        if (databaseQuery != null) {
            Map<String, Object> queryArguments = databaseQueryArguments(arguments);
            return toDatabaseQueryCallToolResult(databaseQuery, queryArguments,
                databaseQueryInvokeService.invoke(databaseQuery, queryArguments));
        }
        return toScriptCallToolResult(scriptExecuteService.execute(executionTargetRouter.routeSqlQuery(arguments)));
    }

    private String sqlGatewayRuntimeLevel(Map<String, Object> arguments) {
        String script = firstText(text(arguments, "script"), text(arguments, "sql"));
        if (script == null || script.isBlank()) {
            DatabaseQueryConfig query = businessDatabaseQueryTemplate(arguments);
            script = query == null ? null : query.getSqlTemplate();
        }
        if (script == null || script.isBlank()) {
            return "sql";
        }
        try {
            return scriptExecuteService.extractStatements(script).size() > 1 ? "sql_script" : "sql";
        } catch (Exception ignored) {
            return script.contains(";") ? "sql_script" : "sql";
        }
    }

    private McpSchema.CallToolResult executeSqlGateway(Map<String, Object> arguments) {
        DatabaseQueryConfig databaseQuery = businessDatabaseQueryTemplate(arguments);
        if (databaseQuery != null) {
            Map<String, Object> queryArguments = databaseQueryArguments(arguments);
            ToolOutput output = databaseQueryInvokeService.invoke(databaseQuery, queryArguments);
            return toDatabaseQueryCallToolResult(databaseQuery, queryArguments, output);
        }
        Map<String, Object> routed = executionTargetRouter.routeSqlQuery(arguments);
        if (shouldExecuteAsScript(routed)) {
            return toScriptCallToolResult(scriptExecuteService.execute(toScriptArguments(routed)));
        }
        return toCallToolResult(executeService.execute(routed));
    }

    private McpSchema.CallToolResult executeDatasourceSql(SqlDatasourceConfig datasource, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (shouldExecuteAsScript(request)) {
            return toScriptCallToolResult(scriptExecuteService.execute(datasource, toScriptArguments(request)));
        }
        return toCallToolResult(executeService.execute(datasource, request));
    }

    private boolean shouldExecuteAsScript(Map<String, Object> arguments) {
        if (arguments == null) {
            return false;
        }
        String sql = text(arguments, "sql");
        String script = text(arguments, "script");
        if (script != null && !script.isBlank()) {
            return true;
        }
        if (sql == null || sql.isBlank()) {
            String template = firstText(
                text(arguments, "template"),
                firstText(text(arguments, "templateId"), text(arguments, "template_id"))
            );
            String templateBody = sqlTemplateBody(template);
            return templateBody != null
                && (AgentRuntimeTemplateDsl.looksLikeDsl(templateBody)
                || SqlStatementExtractor.splitStatements(templateBody).size() > 1);
        }
        return SqlStatementExtractor.splitStatements(sql).size() > 1;
    }

    private String sqlTemplateBody(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return null;
        }
        return sqlTemplateService.listEnabled().stream()
            .filter(template -> template != null && equalsIgnoreCase(template.getCode(), templateCode))
            .map(SqlTemplateConfig::getSqlTemplate)
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> toScriptArguments(Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String script = firstText(text(values, "script"), text(values, "sql"));
        values.put("script", script);
        values.remove("sql");
        if (values.containsKey("maxRows") && !values.containsKey("maxRowsPerStatement")) {
            values.put("maxRowsPerStatement", values.get("maxRows"));
        }
        return values;
    }

    private DatabaseQueryConfig businessDatabaseQueryTemplate(Map<String, Object> arguments) {
        String template = firstText(
            text(arguments, "template"),
            firstText(text(arguments, "templateId"), text(arguments, "template_id"))
        );
        if (template == null || template.isBlank()) {
            return null;
        }
        return databaseQueryConfigService.listEnabled().stream()
            .filter(config -> config != null && equalsIgnoreCase(config.getToolName(), template))
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> databaseQueryArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Object parameters = arguments.get("parameters");
        if (parameters instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> values = new LinkedHashMap<>(arguments);
        values.remove("template");
        values.remove("templateId");
        values.remove("template_id");
        values.remove("executionContext");
        values.remove("mcpExecutionContext");
        return values;
    }

    private McpSchema.CallToolResult toDatabaseQueryCallToolResult(DatabaseQueryConfig config,
                                                                   Map<String, Object> arguments,
                                                                   ToolOutput output) {
        Object structured = standardResultFactory.fromDatabaseQuery(config, arguments, output);
        boolean success = output != null && output.isSuccess();
        String text = success
            ? summarizeDatabaseQueryData(output.getData())
            : output == null ? "database_query returned no output" : output.getErrorMessage();
        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!success)
            .build();
    }

    private String summarizeDatabaseQueryData(Object data) {
        if (data == null) {
            return "database_query executed successfully";
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", map.get("mode"));
            summary.put("schemaVersion", map.get("schemaVersion"));
            summary.put("tool", map.get("tool"));
            summary.put("execution", map.get("execution"));
            summary.put("resultSetCount", map.get("resultSetCount"));
            summary.put("structuredContent", "Complete workflow steps, result semantics and row data are available in structuredContent.data");
            try {
                return com.chatchat.agents.protocol.ModelProtocolJson.compact(summary);
            } catch (RuntimeException ignored) {
                return "database_query workflow executed; complete data is available in structuredContent.data";
            }
        }
        return String.valueOf(data);
    }

    private String summarizeMetadataSearchResult(Map<String, Object> result) {
        List<Map<String, Object>> results = listOfMaps(result == null ? null : result.get("results"));
        if (results.isEmpty()) {
            return "SQL 元数据检索完成；未返回命中的表。";
        }
        StringBuilder text = new StringBuilder("SQL 元数据检索完成；命中 ")
            .append(results.size())
            .append(" 张表。");
        int tableIndex = 1;
        for (Map<String, Object> item : results.stream().limit(3).toList()) {
            Map<String, Object> location = objectMap(item.get("location"));
            String database = firstText(text(location, "schema"), text(location, "database"));
            String table = firstText(text(location, "tableName"), text(location, "table"));
            String tableComment = text(location, "tableComment");
            List<Map<String, Object>> columns = listOfMaps(item.get("columns"));
            text.append("\n\n## 表 ").append(tableIndex++).append(": `")
                .append(escapeMarkdownInline(joinTableName(database, table)))
                .append("`");
            if (tableComment != null && !tableComment.isBlank()) {
                text.append("\n\n").append(tableComment);
            }
            text.append("\n\n字段数：").append(item.getOrDefault("columnCount", columns.size()));
            if (columns.isEmpty()) {
                text.append("\n\n本次文本摘要未包含缓存字段元数据；可使用 includeColumns=true 重新调用 sql_metadata_search。");
                continue;
            }
            text.append("\n\n| # | 字段 | 类型 | 键 | 可空 | 注释 |\n");
            text.append("|---:|---|---|---|---|---|\n");
            int columnIndex = 1;
            List<Map<String, Object>> summaryColumns = limitedList(columns, sqlMetadataSearchSummaryMaxColumns());
            for (Map<String, Object> column : summaryColumns) {
                String name = firstText(firstText(text(column, "name"), text(column, "columnName")), text(column, "COLUMN_NAME"));
                String type = firstText(
                    firstText(text(column, "columnType"), text(column, "dataType")),
                    firstText(text(column, "type"), text(column, "COLUMN_TYPE"))
                );
                String key = firstText(text(column, "columnKey"), text(column, "key"));
                String comment = firstText(
                    firstText(text(column, "comment"), text(column, "remarks")),
                    text(column, "COLUMN_COMMENT")
                );
                text.append("| ").append(columnIndex++)
                    .append(" | `").append(escapeMarkdownInline(name)).append("`")
                    .append(" | `").append(escapeMarkdownInline(type)).append("`")
                    .append(" | ").append(escapeMarkdownCell(key))
                    .append(" | ").append(escapeMarkdownCell(nullableText(column.get("nullable"))))
                    .append(" | ").append(escapeMarkdownCell(comment))
                    .append(" |\n");
            }
            if (summaryColumns.size() < columns.size()) {
                text.append("\n文本摘要仅展示前 ").append(summaryColumns.size())
                    .append(" 个字段；完整字段在 structuredContent.results[].columns 中保留。");
            }
        }
        if (results.size() > 3) {
            text.append("\n\n文本摘要仅展示前 3 张命中表；完整命中结果在 structuredContent.results 中保留。");
        }
        String value = text.toString();
        int maxChars = sqlMetadataSearchSummaryMaxChars();
        return maxChars < 0 || value.length() <= maxChars
            ? value
            : value.substring(0, maxChars) + "\n\n[元数据摘要已截断；完整 structuredContent 已保留]";
    }

    private String summarizeMetadataSearchResultV2(Map<String, Object> result) {
        List<Map<String, Object>> tableCatalog = listOfMaps(result == null ? null : result.get("tableCatalog"));
        List<Map<String, Object>> topTables = listOfMaps(result == null ? null : result.get("topTables"));
        if (topTables.isEmpty()) {
            topTables = listOfMaps(result == null ? null : result.get("results"));
        }
        int totalMatched = intValue(result == null ? null : result.get("totalMatched"), tableCatalog.size());
        int catalogReturnedCount = intValue(result == null ? null : result.get("catalogReturnedCount"), tableCatalog.size());
        int returnedDetailCount = intValue(result == null ? null : result.get("returnedDetailCount"), topTables.size());
        boolean hasMore = booleanValue(result == null ? null : result.get("hasMore"));
        boolean catalogTruncated = booleanValue(result == null ? null : result.get("catalogTruncated"));
        boolean detailTruncated = booleanValue(result == null ? null : result.get("detailTruncated"));
        if (totalMatched <= 0 && tableCatalog.isEmpty() && topTables.isEmpty()) {
            return "SQL metadata search completed; no matched tables were returned.";
        }
        StringBuilder text = new StringBuilder("SQL metadata search completed. ")
            .append("totalMatched=").append(totalMatched)
            .append(", catalogReturnedCount=").append(catalogReturnedCount)
            .append(", catalogTruncated=").append(catalogTruncated)
            .append(", returnedDetailCount=").append(returnedDetailCount)
            .append(", hasMore=").append(hasMore)
            .append(", detailTruncated=").append(detailTruncated)
            .append(".\n\n")
            .append("Use tableCatalog as the matched table list and topTables as the detailed Top ")
            .append(returnedDetailCount)
            .append(" tables. Do not describe topTables/results as the full matched table count.");
        appendMetadataCatalogSummary(text, tableCatalog);
        appendMetadataTopTableSummary(text, topTables);
        return boundedMetadataSummary(text.toString());
    }

    private void appendMetadataCatalogSummary(StringBuilder text, List<Map<String, Object>> tableCatalog) {
        if (tableCatalog == null || tableCatalog.isEmpty()) {
            return;
        }
        text.append("\n\n## Matched table catalog");
        text.append("\n\n| # | Database | Schema | Table | Comment |\n");
        text.append("|---:|---|---|---|---|\n");
        int catalogIndex = 1;
        for (Map<String, Object> item : tableCatalog.stream().limit(20).toList()) {
            text.append("| ").append(catalogIndex++)
                .append(" | ").append(escapeMarkdownCell(text(item, "database")))
                .append(" | ").append(escapeMarkdownCell(text(item, "schema")))
                .append(" | `").append(escapeMarkdownInline(firstText(text(item, "tableName"), text(item, "table")))).append("`")
                .append(" | ").append(escapeMarkdownCell(text(item, "tableComment")))
                .append(" |\n");
        }
        if (tableCatalog.size() > 20) {
            text.append("\nText summary shows first 20 catalog rows; full returned catalog is in structuredContent.tableCatalog.");
        }
    }

    private void appendMetadataTopTableSummary(StringBuilder text, List<Map<String, Object>> topTables) {
        if (topTables == null || topTables.isEmpty()) {
            return;
        }
        text.append("\n\n## Top table details");
        int tableIndex = 1;
        for (Map<String, Object> item : topTables.stream().limit(5).toList()) {
            Map<String, Object> location = objectMap(item.get("location"));
            String database = firstText(text(location, "schema"), text(location, "database"));
            String table = firstText(text(location, "tableName"), text(location, "table"));
            String tableComment = text(location, "tableComment");
            List<Map<String, Object>> columns = listOfMaps(item.get("columns"));
            text.append("\n\n### Table ").append(tableIndex++).append(": `")
                .append(escapeMarkdownInline(joinTableName(database, table)))
                .append("`");
            if (tableComment != null && !tableComment.isBlank()) {
                text.append("\n\n").append(tableComment);
            }
            text.append("\n\ncolumnCount: ").append(item.getOrDefault("columnCount", columns.size()));
            if (columns.isEmpty()) {
                text.append("\n\nNo cached columns were included in this detail item.");
                continue;
            }
            text.append("\n\n| # | Column | Type | Key | Nullable | Comment |\n");
            text.append("|---:|---|---|---|---|---|\n");
            int columnIndex = 1;
            List<Map<String, Object>> summaryColumns = limitedList(columns, sqlMetadataSearchSummaryMaxColumns());
            for (Map<String, Object> column : summaryColumns) {
                String name = firstText(firstText(text(column, "name"), text(column, "columnName")), text(column, "COLUMN_NAME"));
                String type = firstText(
                    firstText(text(column, "columnType"), text(column, "dataType")),
                    firstText(text(column, "type"), text(column, "COLUMN_TYPE"))
                );
                String key = firstText(text(column, "columnKey"), text(column, "key"));
                String comment = firstText(
                    firstText(text(column, "comment"), text(column, "remarks")),
                    text(column, "COLUMN_COMMENT")
                );
                text.append("| ").append(columnIndex++)
                    .append(" | `").append(escapeMarkdownInline(name)).append("`")
                    .append(" | `").append(escapeMarkdownInline(type)).append("`")
                    .append(" | ").append(escapeMarkdownCell(key))
                    .append(" | ").append(escapeMarkdownCell(nullableText(column.get("nullable"))))
                    .append(" | ").append(escapeMarkdownCell(comment))
                    .append(" |\n");
            }
            if (summaryColumns.size() < columns.size()) {
                text.append("\nText summary shows first ").append(summaryColumns.size())
                    .append(" columns; full columns are in structuredContent.topTables[].columns.");
            }
        }
    }

    private String boundedMetadataSummary(String value) {
        int maxChars = sqlMetadataSearchSummaryMaxChars();
        return maxChars < 0 || value.length() <= maxChars
            ? value
            : value.substring(0, maxChars) + "\n\n[Metadata text summary truncated; full structuredContent is preserved.]";
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private McpServerFeatures.SyncToolSpecification sqlMetadataSearchTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("sql_metadata_search")
            .title("SQL metadata table search")
            .description("Read-only Lucene-backed metadata retrieval tool for locating datasource/database/table entries before SQL template execution. "
                + "Use this before database_ops_template_search and sql_query_execute when the user mentions a table name, table comment, business meaning, or when schema/database is unknown. "
                + "The structured result separates tableCatalog, the lightweight matched table list, from topTables, the detailed Top 5 tables with cached column metadata. "
                + "Never treat topTables/results as the full matched table count; use totalMatched, catalogReturnedCount, and catalogTruncated. "
                + "In user-facing answers always cite exact physical identifiers from tableCatalog/topTables.location and list exact topTables.columns under the corresponding table when returned; never replace identifiers with translated business descriptions or guessed fields. "
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
                    long startedAt = System.currentTimeMillis();
                    Map<String, Object> result = metadataSearchService.search(request.arguments());
                    long searchDurationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                    long summaryStartedAt = System.currentTimeMillis();
                    String summary = summarizeMetadataSearchResultV2(result);
                    long summaryDurationMs = Math.max(0L, System.currentTimeMillis() - summaryStartedAt);
                    log.info("sql_metadata_search tool result prepared count={} searchDurationMs={} summaryDurationMs={} diagnostics={}",
                        result == null ? null : result.get("count"),
                        searchDurationMs,
                        summaryDurationMs,
                        result == null ? null : result.get("diagnostics"));
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(summary)
                        .structuredContent(result)
                        .isError(false)
                        .build();
                }))
            .build();
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "只读 SQL。禁止多语句和注释。"),
            "template", Map.of("type", "string", "description", "Existing SQL templateId from this datasource asset's authorizedSqlTemplates or database_ops_template_search result. Do not invent names."),
            "parameters", Map.of(
                "type", "object",
                "description", "Template parameters object. Use exactly the fields required by database_ops_template_search.templates[].parameterSchema; do not put template parameters at the top level.",
                "additionalProperties", true
            ),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 60),
            "maxRows", Map.of("type", "integer", "minimum", executeService.minRowsLimit(), "maximum", executeService.maxRowsLimit()),
            "purpose", Map.of("type", "string", "description", "查询目的，必须展示给用户确认并写入审计"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of(), false, null, null);
    }

    private McpSchema.JsonSchema gatewayInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "sql", Map.of("type", "string", "description", "Read-only SQL. Comments are allowed and stripped. Multiple read-only statements are accepted and automatically executed as a SQL script with multiple result sets. Writes, DDL, and permission changes are forbidden."),
            "script", Map.of("type", "string", "description", "Optional read-only SQL script alias. Prefer sql unless explicitly invoking multi-statement analysis."),
            "template", Map.of("type", "string", "description", "Existing SQL templateId from database_ops_template_search.templates[].templateId for the selected datasource. Do not invent names."),
            "parameters", Map.of(
                "type", "object",
                "description", "Template parameters object. Use exactly the fields required by database_ops_template_search.templates[].parameterSchema; do not put template parameters at the top level.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as env, cluster, database, databaseRole, targetType, service, or labels"
            ),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 60),
            "maxRows", Map.of("type", "integer", "minimum", executeService.minRowsLimit(), "maximum", executeService.maxRowsLimit()),
            "purpose", Map.of("type", "string", "description", "Query purpose for confirmation and audit"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of("executionContext"), false, null, null);
    }

    private McpSchema.JsonSchema scriptGatewayInputSchema() {
        return new McpSchema.JsonSchema("object", Map.of(
            "script", Map.of("type", "string", "description", "Semicolon-separated read-only SQL analysis script. Each statement must be SELECT, SHOW, DESCRIBE/DESC, or EXPLAIN. Comments and writes are forbidden."),
            "template", Map.of("type", "string", "description", "Registered business query template name returned by business_query_template_search. Multi-SQL/DAG templates should use this field instead of copying their SQL into script."),
            "templateId", Map.of("type", "string", "description", "Alias of template for compatibility."),
            "parameters", Map.of(
                "type", "object",
                "description", "Business template parameters. Use fields declared by business_query_template_search.templates[].parameterSchema.",
                "additionalProperties", true
            ),
            "executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as env, cluster, database, databaseRole, targetType, service, or labels"
            ),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 300),
            "maxRowsPerStatement", Map.of("type", "integer", "minimum", executeService.minRowsLimit(), "maximum", executeService.maxRowsLimit()),
            "purpose", Map.of("type", "string", "description", "Query purpose for confirmation and audit"),
            "sourceTaskId", Map.of("type", "string")
        ), List.of("executionContext"), false, null, null);
    }

    private McpSchema.JsonSchema metadataSearchInputSchema() {
        return new McpSchema.JsonSchema("object", Map.ofEntries(
            Map.entry("query", Map.of("type", "string", "description", "Free-text table/database search query, for example a table name, table comment/business meaning, database description, schema.table, or asset.database.table path.")),
            Map.entry("searchTerm", Map.of("type", "string", "description", "Alias of query for compatibility. Prefer query.")),
            Map.entry("keyword", Map.of("type", "string", "description", "Alias of query for compatibility. Prefer query.")),
            Map.entry("keywords", Map.of("type", "string", "description", "Alias of query for compatibility. Prefer query.")),
            Map.entry("searchText", Map.of("type", "string", "description", "Alias of query for compatibility. Prefer query.")),
            Map.entry("tableName", Map.of("type", "string", "description", "Optional explicit table name from the user request.")),
            Map.entry("database", Map.of("type", "string", "description", "Optional database/schema name filter.")),
            Map.entry("schema", Map.of("type", "string", "description", "Alias of database.")),
            Map.entry("assetId", Map.of("type", "string", "description", "Optional logical datasource asset id resolved by asset discovery or configured default data asset.")),
            Map.entry("assetName", Map.of("type", "string", "description", "Optional logical datasource asset name.")),
            Map.entry("defaultDataAsset", Map.of(
                "type", "object",
                "description", "Configured default data asset context copied from the agent skill; used only as metadata retrieval scope fallback."
            )),
            Map.entry("executionContext", Map.of(
                "type", "object",
                "description", "Logical datasource context such as assetId, assetName, env, databaseType, database/schema, or tableName. Concrete endpoint fields are forbidden."
            )),
            Map.entry("limit", Map.of("type", "integer", "minimum", 1, "description", "Deprecated legacy field. It is not used for detailed table count; use catalogLimit or detailLimit instead.")),
            Map.entry("catalogLimit", Map.of("type", "integer", "minimum", 1, "description", "Optional maximum number of lightweight tableCatalog rows returned. Defaults to 200.")),
            Map.entry("detailLimit", Map.of("type", "integer", "minimum", 1, "description", "Optional maximum number of detailed topTables returned. Defaults to 5.")),
            Map.entry("includeColumns", Map.of("type", "boolean", "description", "Optional; true includes cached column metadata for matched tables. Defaults to true; set false only for lightweight routing lookup."))
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
        meta.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "sql_datasource:template_execution",
            "Governed SQL query execution",
            List.of("sql_datasource", "database_query"),
            "Execute a registered read-only SQL template against a logically routed SQL datasource.",
            List.of("A bound plan already has a registered template id, validated parameters and a logical execution target."),
            List.of("Ad-hoc raw SQL", "Write or DDL statements", "Schema discovery", "Selecting or replacing Agent-bound tools")
        ));
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

    private Map<String, Object> scriptGatewayMeta() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", "sql_gateway");
        governance.put("operation_type", "read_sql_script");
        governance.put("risk_level", "high");
        governance.put("data_scope", "database:routed");
        governance.put("user_visible", true);
        governance.put("confirmation", mutableMap("default", "ask_before_execute", "allow_user_override", false));
        governance.put("input_policy", mutableMap(
            "must_show_parameters", true,
            "required_preview_params", List.of("template", "parameters", "script", "executionContext", "timeoutSeconds", "maxRowsPerStatement", "purpose", "sourceTaskId")
        ));
        governance.put("audit", mutableMap("enabled", true, "log_params", true, "log_result_summary", true));
        Map<String, Object> meta = new LinkedHashMap<>(
            governanceFactory.toMeta("sql_gateway", "sql_script_execute", governance, null));
        meta.put("runtime_action", "confirm_required");
        meta.put("runtimeAction", "confirm_required");
        meta.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "sql_datasource:script_execution",
            "Governed read-only SQL script execution",
            List.of("sql_datasource", "database_query"),
            "Execute a registered business query DAG/multi-SQL template or a bounded multi-statement read-only SQL script against a logically routed datasource.",
            List.of("A bound workflow requires a registered DAG/multi-SQL business template or multiple read-only result sets and has a logical target."),
            List.of("Write or DDL statements", "Schema discovery", "Selecting or replacing Agent-bound tools")
        ));
        meta.put("allowedStatements", List.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN"));
        meta.put("maxStatements", 10);
        meta.put("returnsMultipleResultSets", true);
        meta.put("templateRegistrySupported", true);
        meta.put("businessQueryWorkflowSupported", true);
        meta.put("targetRoutingRequired", true);
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("forbiddenOperations", List.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "SET", "USE", "CALL", "EXEC"));
        meta.put("assetMetadata", assetMetadataFactory.gateway(
            "sql_datasource",
            datasourceConfigService.listEnabled().stream().map(assetMetadataFactory::sqlDatasource).toList(),
            List.of("datasourceId", "jdbcUrl", "url", "connectionString")
        ));
        meta.put("mcp_tool_limit", concurrencyManager.limitMeta("sql_script_execute", "sql_script"));
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
        meta.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "sql_datasource:schema_discovery",
            "SQL schema metadata discovery",
            List.of("sql_datasource"),
            "Search indexed database, table and column metadata without executing business SQL.",
            List.of("The model needs schema facts to understand or prepare a query for a bound SQL tool."),
            List.of("Executing SQL", "Reading business row data", "Selecting or replacing Agent-bound tools")
        ));
        meta.put("outputSchema", SqlMetadataSearchService.RESULT_SCHEMA_VERSION);
        meta.put("toolResultInstruction", String.join("\n",
            "SQL metadata search result contract:",
            "- totalMatched is the actual matched table count.",
            "- tableCatalog is the lightweight matched table catalog; use it to understand the candidate table range.",
            "- topTables contains only the highest-ranked detailed tables and is limited by detailLimit.",
            "- topTables/results do not represent all matched tables.",
            "- hasMore/detailTruncated indicates additional matched tables do not have detailed payloads in this response."
        ));
        meta.put("resultSchema", mutableMap(
            "schemaVersion", SqlMetadataSearchService.RESULT_SCHEMA_VERSION,
            "totalMatched", "Actual matched table count before detail limiting.",
            "catalogReturnedCount", "Number of lightweight tableCatalog rows returned.",
            "catalogTruncated", "True when tableCatalog was capped by catalogLimit.",
            "detailLimit", "Maximum number of detailed topTables returned.",
            "returnedDetailCount", "Number of detailed topTables returned.",
            "hasMore", "True when matched tables remain outside the detailed topTables payload.",
            "tableCatalog", "Lightweight matched table catalog with database/schema/tableName/tableComment/score. This is the candidate range.",
            "topTables", "Detailed highest-ranked tables with columns and SQL execution binding. This is not the full matched set.",
            "results", "Backward-compatible alias of topTables."
        ));
        meta.put("doesNotExecuteSql", true);
        meta.put("indexBackend", "lucene_metadata_table_index");
        meta.put("forbiddenTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString"));
        meta.put("resultShape", mutableMap(
            "catalogPath", "tableCatalog",
            "detailPath", "topTables",
            "tableLocationPath", "topTables[].location",
            "executionContextPath", "topTables[].sqlExecutionBinding.executionContext",
            "templateParameterPath", "topTables[].sqlExecutionBinding.parameters"
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

    private McpSchema.CallToolResult toScriptCallToolResult(SqlScriptResult result) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(result.success() ? "SQL script completed" : result.errorMessage())
            .structuredContent(standardResultFactory.fromSqlScript(result))
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
            "templateDsl", templateDslMetadata(template),
            "intentSignals", readStringList(template.getIntentSignalsJson()),
            "parameterSchema", parameterSchema,
            "requiredParameters", requiredParameters,
            "parameterContract", parameterContract(template.getCode(), parameterSchema),
            "invocationExample", invocationExample(template.getCode(), parameterSchema),
            "rawExecutionSpecReturned", false
        );
    }

    private Map<String, Object> templateDslMetadata(SqlTemplateConfig template) {
        String body = template == null ? null : template.getSqlTemplate();
        String code = template == null ? null : template.getCode();
        if (!AgentRuntimeTemplateDsl.looksLikeDsl(body)) {
            return mutableMap(
                "schemaVersion", AgentRuntimeTemplateDsl.SCHEMA_VERSION,
                "dsl", false,
                "templateCode", code,
                "templateType", "DB_SQL",
                "executionMode", "SINGLE_OR_LEGACY",
                "executorStepType", "SQL",
                "modelHint", "Legacy SQL template body; execute by templateId and parameters from parameterContract."
            );
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>(AgentRuntimeTemplateDsl.metadata(
                AgentRuntimeTemplateDsl.parse(body, code, "DB_SQL", "SQL")
            ));
            metadata.put("dsl", true);
            metadata.put("modelHint", "Use templateDsl.steps[].stepName/analysisHint to judge whether this template covers the user intent. Execute by templateId; do not inline raw SQL.");
            return metadata;
        } catch (IllegalArgumentException ex) {
            return mutableMap(
                "schemaVersion", AgentRuntimeTemplateDsl.SCHEMA_VERSION,
                "dsl", true,
                "templateCode", code,
                "templateType", "DB_SQL",
                "valid", false,
                "error", ex.getMessage(),
                "modelHint", "DSL template is invalid and should not be selected until fixed."
            );
        }
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
            "source", "database_ops_template_search.templates[].templateId",
            "allowedSet", "authorizedSqlTemplates[].templateId or authorizedSqlTemplatesByAsset[].templates[].templateId",
            "selectionFields", List.of("templateId", "name", "description", "databaseType", "intentSignals",
                "parameterSchema", "requiredParameters", "parameterContract", "invocationExample"),
            "mustUseDiscoveredTemplate", true,
            "metadataWorkflow", "For table structure analysis, discover schemas/tables first (for example MYSQL_SCHEMA_TABLE_OVERVIEW or MYSQL_TABLE_LOCATION), then call the table metadata template with the resolved schemaName.",
            "onNoMatch", "call database_ops_template_search with executionContext; if no authorized template is returned, either use explicit read-only sql when policy permits or explain that no existing authorized template can satisfy the request",
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
            "executionContext", mutableMap("assetName", "<logical datasource assetName from user context or template routing>", "env", "<env>")
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

    private String text(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private Map<String, Object> mutableMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                values.add((Map<String, Object>) map);
            }
        }
        return values;
    }

    private List<Map<String, Object>> limitedList(List<Map<String, Object>> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (limit < 0 || values.size() <= limit) {
            return values;
        }
        return values.stream().limit(Math.max(0, limit)).toList();
    }

    private int sqlMetadataSearchSummaryMaxChars() {
        ChatChatMcpServerProperties.OutputProperties output = outputProperties();
        return output == null ? 6_000 : output.getSqlMetadataSearchSummaryMaxChars();
    }

    private int sqlMetadataSearchSummaryMaxColumns() {
        ChatChatMcpServerProperties.OutputProperties output = outputProperties();
        return output == null ? 30 : output.getSqlMetadataSearchSummaryMaxColumns();
    }

    private ChatChatMcpServerProperties.OutputProperties outputProperties() {
        return serverProperties == null ? new ChatChatMcpServerProperties.OutputProperties() : serverProperties.getOutput();
    }

    private String joinTableName(String database, String table) {
        if (database == null || database.isBlank()) {
            return table == null ? "-" : table;
        }
        if (table == null || table.isBlank()) {
            return database;
        }
        return database + "." + table;
    }

    private String nullableText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text)) {
            return "YES";
        }
        if ("false".equalsIgnoreCase(text)) {
            return "NO";
        }
        return text;
    }

    private String escapeMarkdownInline(String value) {
        return value == null ? "" : value.replace("`", "'");
    }

    private String escapeMarkdownCell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("SQL MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
