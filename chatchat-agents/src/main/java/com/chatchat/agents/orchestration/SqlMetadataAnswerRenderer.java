package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders SQL table metadata evidence into deterministic Markdown.
 */
class SqlMetadataAnswerRenderer {

    static final String FACT_SCHEMA_VERSION = "sql_metadata_fact_v1";
    static final String SEMANTIC_GATE_SCHEMA_VERSION = "sql_metadata_semantic_gate_v1";

    String render(InterpretationPlanRuntime.ExecutionResult result) {
        return renderEvidence(result).markdown();
    }

    RenderedSqlMetadata renderEvidence(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            return RenderedSqlMetadata.empty();
        }
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            RenderedSqlMetadata rendered = renderStep(step);
            if (!rendered.markdown().isBlank()) {
                return rendered;
            }
        }
        return RenderedSqlMetadata.empty();
    }

    private RenderedSqlMetadata renderStep(InterpretationPlanRuntime.StepExecution step) {
        boolean metadataSearch = step != null && isSqlMetadataSearch(step.toolName());
        if (step == null || (!isSqlQueryExecute(step.toolName()) && !metadataSearch)) {
            return RenderedSqlMetadata.empty();
        }
        Map<String, Object> output = asMap(step.output());
        if (output.isEmpty()) {
            return RenderedSqlMetadata.empty();
        }
        if (metadataSearch) {
            return renderMetadataSearchStep(step, output);
        }
        Map<String, Object> data = sqlDataMap(output);
        Map<String, Object> metadataSearchResult = Map.of();
        Map<String, Object> metadataSearchLocation = asMap(metadataSearchResult.get("location"));
        Map<String, Object> metadataSearchAsset = asMap(metadataSearchResult.get("asset"));
        Map<String, Object> metadataSearchBinding = asMap(metadataSearchResult.get("sqlExecutionBinding"));
        Map<String, Object> metadataSearchBindingParameters = asMap(metadataSearchBinding.get("parameters"));
        Map<String, Object> metadataSearchRoutingContext = asMap(metadataSearchResult.get("routingContext"));
        List<Map<String, Object>> rows = sqlColumnRows(output);
        if (rows.isEmpty() || !looksLikeColumnMetadata(rows)) {
            return RenderedSqlMetadata.empty();
        }
        Map<String, Object> operation = firstNonEmptyMap(output.get("operation"), data.get("operation"));
        Map<String, Object> diagnostics = firstNonEmptyMap(
            operation.get("diagnostics"),
            data.get("diagnostics"),
            output.get("diagnostics")
        );
        Map<String, Object> resolution = firstNonEmptyMap(diagnostics.get("tableResolution"), output.get("tableResolution"), data.get("tableResolution"));
        Map<String, Object> target = firstNonEmptyMap(output.get("target"), diagnostics.get("routedTarget"), data.get("target"));
        Map<String, Object> datasource = asMap(diagnostics.get("datasource"));
        Map<String, Object> firstCandidate = firstMap(resolution.get("candidates"));
        Map<String, Object> executionContext = asMap(diagnostics.get("executionContext"));
        String schema = firstNonBlank(
            text(resolution.get("selectedSchema")),
            text(executionContext.get("schemaName")),
            text(executionContext.get("schema")),
            text(diagnostics.get("schemaName")),
            text(firstCandidate.get("schema")),
            text(firstCandidate.get("database")),
            text(metadataSearchLocation.get("schema")),
            text(metadataSearchLocation.get("database")),
            text(metadataSearchBindingParameters.get("schemaName")),
            text(metadataSearchBindingParameters.get("databaseName"))
        );
        String table = firstNonBlank(
            text(resolution.get("selectedTable")),
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name")),
            text(diagnostics.get("tableName")),
            text(firstCandidate.get("table")),
            text(metadataSearchLocation.get("table")),
            text(metadataSearchLocation.get("tableName")),
            text(metadataSearchBindingParameters.get("tableName"))
        );
        String datasourceName = firstNonBlank(
            text(target.get("name")),
            text(target.get("datasourceName")),
            text(datasource.get("name")),
            text(target.get("toolName")),
            text(datasource.get("toolName")),
            text(metadataSearchAsset.get("name")),
            text(metadataSearchAsset.get("title")),
            text(metadataSearchAsset.get("toolName")),
            text(metadataSearchRoutingContext.get("assetName"))
        );
        String tableType = firstNonBlank(text(firstCandidate.get("tableType")), text(metadataSearchLocation.get("tableType")));
        String tableRows = firstNonBlank(text(firstCandidate.get("tableRows")), text(data.get("tableRows")), text(metadataSearchLocation.get("tableRows")));
        String sql = text(operation.get("statement"));
        String requestedTable = firstNonBlank(
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name")),
            text(diagnostics.get("tableName")),
            text(asMap(diagnostics.get("templateParameters")).get("tableName")),
            text(asMap(diagnostics.get("templateParameters")).get("table_name")),
            text(metadataSearchBindingParameters.get("tableName")),
            text(metadataSearchLocation.get("tableName")),
            text(metadataSearchLocation.get("table"))
        );
        String templateId = firstNonBlank(
            text(diagnostics.get("templateId")),
            text(output.get("templateId")),
            text(output.get("template")),
            metadataSearch ? "SQL_METADATA_SEARCH" : ""
        );
        Map<String, Object> gate = semanticGate(step, rows, schema, table, requestedTable, templateId, sql, diagnostics);

        StringBuilder markdown = new StringBuilder();
        markdown.append("## 元数据依据\n\n");
        markdown.append("- 数据源：").append(emptyAsDash(datasourceName)).append("\n");
        markdown.append("- 表定位：`").append(qualifiedName(schema, table)).append("`");
        if (!blank(tableType)) {
            markdown.append("，类型：`").append(tableType).append("`");
        }
        markdown.append("\n");
        markdown.append("- 表行数：").append(blank(tableRows) ? "未返回" : "`TABLE_ROWS = " + tableRows + "`").append("\n");
        markdown.append("- 字段数：`").append(rows.size()).append("`\n");
        markdown.append("- 结构明细：已从 MCP 结构化输出 `rows` 获取，下面表格即为本次返回的字段证据。\n");
        if (!blank(sql)) {
            markdown.append("- 依据 SQL：`").append(escapeInline(sql)).append("`\n");
        }
        markdown.append("\n");

        markdown.append("## 字段结构\n\n");
        markdown.append("| # | 字段名 | 类型 | 可空 | 默认值 | 键 | 额外信息 | 注释 |\n");
        markdown.append("|---:|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            markdown.append("| ").append(i + 1)
                .append(" | `").append(escapeCell(firstNonBlank(value(row, "COLUMN_NAME", "column_name"), ""))).append("`")
                .append(" | `").append(escapeCell(firstNonBlank(value(row, "COLUMN_TYPE", "column_type"), ""))).append("`")
                .append(" | ").append(escapeCell(firstNonBlank(value(row, "IS_NULLABLE", "is_nullable"), "")))
                .append(" | ").append(escapeCell(defaultValue(row)))
                .append(" | ").append(escapeCell(firstNonBlank(value(row, "COLUMN_KEY", "column_key"), "")))
                .append(" | ").append(escapeCell(firstNonBlank(value(row, "EXTRA", "extra"), "")))
                .append(" | ").append(escapeCell(firstNonBlank(value(row, "COLUMN_COMMENT", "column_comment"), "")))
                .append(" |\n");
        }

        String recommendation = metricRecommendation(rows, schema, table);
        if (!recommendation.isBlank()) {
            markdown.append("\n").append(recommendation);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", FACT_SCHEMA_VERSION);
        metadata.put("source", metadataSearch ? "mcp_sql_metadata_search_results_columns" : "mcp_sql_query_execute_rows");
        metadata.put("dataTruthValidated", true);
        metadata.put("toolName", step.toolName());
        metadata.put("stepId", step.stepId());
        metadata.put("datasourceName", datasourceName);
        metadata.put("schema", schema);
        metadata.put("table", table);
        metadata.put("requestedTable", requestedTable);
        metadata.put("tableType", tableType);
        metadata.put("tableRows", tableRows);
        metadata.put("columnCount", rows.size());
        metadata.put("rowCount", integerValue(data.get("rowCount")));
        metadata.put("templateId", templateId);
        metadata.put("statementPresent", !blank(sql));
        metadata.put("semanticGate", gate);
        metadata.put("semanticGatePassed", Boolean.TRUE.equals(gate.get("passed")));
        metadata.put("semanticGateReason", gate.get("reason"));
        metadata.entrySet().removeIf(entry -> entry.getValue() == null);
        return new RenderedSqlMetadata(markdown.toString().trim(), Map.copyOf(metadata));
    }

    private RenderedSqlMetadata renderMetadataSearchStep(InterpretationPlanRuntime.StepExecution step,
                                                          Map<String, Object> output) {
        Map<String, Object> payload = metadataSearchPayload(output, 0);
        List<Map<String, Object>> catalog = rowMaps(payload.get("tableCatalog"));
        List<Map<String, Object>> details = rowMaps(payload.get("topTables"));
        if (details.isEmpty()) {
            details = rowMaps(payload.get("results"));
        }
        if (catalog.isEmpty() && !details.isEmpty()) {
            List<Map<String, Object>> derived = new ArrayList<>();
            for (Map<String, Object> detail : details) {
                Map<String, Object> location = asMap(detail.get("location"));
                derived.add(location.isEmpty() ? detail : location);
            }
            catalog = derived;
        }

        int totalMatched = firstInteger(payload.get("totalMatched"), catalog.size());
        boolean catalogTruncated = booleanValue(payload.get("catalogTruncated"));
        Set<String> evidenceIdentifiers = new LinkedHashSet<>();
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 实际检索到的数据库对象\n\n");
        markdown.append("> 以下数据库、Schema、物理表名和字段均直接来自本次 `sql_metadata_search` 结构化结果；未返回的内容不会推断或补充。\n\n");
        if (catalog.isEmpty()) {
            markdown.append("本次工具未检索到可验证的物理表。不会提供推测的分层、表名示例或常见表推荐。\n");
        } else {
            markdown.append("- 匹配数量：`").append(totalMatched).append("`\n");
            markdown.append("- 已返回物理表：`").append(catalog.size()).append("`\n");
            markdown.append("- 表目录是否截断：`").append(catalogTruncated).append("`\n\n");
            markdown.append("| # | 数据库 | Schema | 物理表名 | 类型 | 表说明 |\n");
            markdown.append("|---:|---|---|---|---|---|\n");
            for (int index = 0; index < catalog.size(); index++) {
                Map<String, Object> table = catalog.get(index);
                String database = firstNonBlank(value(table, "database", "databaseName", "catalog"), "");
                String schema = firstNonBlank(value(table, "schema", "schemaName"), "");
                String tableName = firstNonBlank(value(table, "tableName", "table"), "");
                addIdentifier(evidenceIdentifiers, database);
                addIdentifier(evidenceIdentifiers, schema);
                addIdentifier(evidenceIdentifiers, tableName);
                addIdentifier(evidenceIdentifiers, qualifiedName(schema, tableName));
                markdown.append("| ").append(index + 1)
                    .append(" | ").append(escapeCell(firstNonBlank(database, "-")))
                    .append(" | ").append(escapeCell(firstNonBlank(schema, "-")))
                    .append(" | `").append(escapeCell(firstNonBlank(tableName, "-"))).append("`")
                    .append(" | ").append(escapeCell(firstNonBlank(value(table, "tableType", "type"), "-")))
                    .append(" | ").append(escapeCell(firstNonBlank(value(table, "tableComment", "comment"), "-")))
                    .append(" |\n");
            }
        }

        int returnedColumnCount = 0;
        for (Map<String, Object> detail : details) {
            Map<String, Object> location = asMap(detail.get("location"));
            Map<String, Object> table = location.isEmpty() ? detail : location;
            List<Map<String, Object>> columns = rowMaps(detail.get("columns"));
            if (columns.isEmpty()) {
                continue;
            }
            returnedColumnCount += columns.size();
            markdown.append("\n## 字段：`").append(escapeInline(qualifiedName(
                firstNonBlank(value(table, "schema", "schemaName", "database", "databaseName"), ""),
                firstNonBlank(value(table, "tableName", "table"), "")
            ))).append("`\n\n");
            markdown.append("| # | 物理字段名 | 类型 | 键 | 可空 | 字段说明 |\n");
            markdown.append("|---:|---|---|---|---|---|\n");
            for (int index = 0; index < columns.size(); index++) {
                Map<String, Object> column = columns.get(index);
                String columnName = firstNonBlank(value(column, "name", "columnName", "COLUMN_NAME"), "");
                addIdentifier(evidenceIdentifiers, columnName);
                markdown.append("| ").append(index + 1)
                    .append(" | `").append(escapeCell(firstNonBlank(columnName, "-"))).append("`")
                    .append(" | `").append(escapeCell(firstNonBlank(value(column, "columnType", "dataType", "COLUMN_TYPE"), "-"))).append("`")
                    .append(" | ").append(escapeCell(firstNonBlank(value(column, "columnKey", "COLUMN_KEY", "key"), "-")))
                    .append(" | ").append(escapeCell(firstNonBlank(value(column, "nullable", "IS_NULLABLE"), "-")))
                    .append(" | ").append(escapeCell(firstNonBlank(value(column, "comment", "columnComment", "COLUMN_COMMENT"), "-")))
                    .append(" |\n");
            }
        }
        if (!catalog.isEmpty() && returnedColumnCount == 0) {
            markdown.append("\n## 字段信息\n\n工具未返回上述匹配表的字段明细，因而不补充示例字段。\n");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", FACT_SCHEMA_VERSION);
        metadata.put("source", "mcp_sql_metadata_search_catalog");
        metadata.put("dataTruthValidated", true);
        metadata.put("authoritativeOnly", true);
        metadata.put("toolName", step.toolName());
        metadata.put("stepId", step.stepId());
        metadata.put("totalMatched", totalMatched);
        metadata.put("catalogReturnedCount", catalog.size());
        metadata.put("catalogTruncated", catalogTruncated);
        metadata.put("columnCount", returnedColumnCount);
        metadata.put("evidenceIdentifiers", List.copyOf(evidenceIdentifiers));
        metadata.put("semanticGatePassed", toolSucceeded(step));
        metadata.put("semanticGateReason", toolSucceeded(step) ? "structured_metadata_catalog_returned" : "metadata_tool_not_successful");
        return new RenderedSqlMetadata(markdown.toString().trim(), Map.copyOf(metadata));
    }

    private void addIdentifier(Set<String> identifiers, String value) {
        if (identifiers != null && !blank(value) && !"-".equals(value)) {
            identifiers.add(value);
        }
    }

    private Map<String, Object> metadataSearchPayload(Object value, int depth) {
        if (value == null || depth > 8) {
            return Map.of();
        }
        Map<String, Object> map = asMap(value);
        if (map.isEmpty()) {
            return Map.of();
        }
        if (map.containsKey("tableCatalog") || map.containsKey("topTables") || map.containsKey("results")
            || map.containsKey("totalMatched")) {
            return map;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            Map<String, Object> nested = metadataSearchPayload(map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return map;
    }

    private int firstInteger(Object value, int fallback) {
        Integer parsed = integerValue(value);
        return parsed == null ? fallback : parsed;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> semanticGate(InterpretationPlanRuntime.StepExecution step,
                                             List<Map<String, Object>> rows,
                                             String schema,
                                             String table,
                                             String requestedTable,
                                             String templateId,
                                             String sql,
                                             Map<String, Object> diagnostics) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", SEMANTIC_GATE_SCHEMA_VERSION);
        boolean dataRowsPresent = rows != null && !rows.isEmpty() && looksLikeColumnMetadata(rows);
        boolean toolSucceeded = toolSucceeded(step);
        boolean schemaResolved = !blank(schema) && !blank(table);
        boolean tableMatchesRequest = !blank(requestedTable) && normalizeIdentifier(requestedTable).equals(normalizeIdentifier(table));
        boolean tableScopedMetadata = isTableMetadataTemplate(templateId)
            || containsIgnoreCase(sql, "information_schema.columns")
            || containsIgnoreCase(text(diagnostics.get("templateId")), "TABLE_METADATA");
        boolean noExecutionFailure = step == null || blank(step.errorMessage());
        values.put("dataRowsPresent", dataRowsPresent);
        values.put("toolSucceeded", toolSucceeded);
        values.put("schemaResolved", schemaResolved);
        values.put("tableMatchesRequest", tableMatchesRequest);
        values.put("tableScopedMetadata", tableScopedMetadata);
        values.put("noExecutionFailure", noExecutionFailure);
        boolean passed = dataRowsPresent
            && toolSucceeded
            && schemaResolved
            && tableMatchesRequest
            && tableScopedMetadata
            && noExecutionFailure;
        values.put("passed", passed);
        values.put("reason", passed ? "mcp_rows_schema_and_table_context_validated" : gateFailureReason(values));
        return values;
    }

    private String gateFailureReason(Map<String, Object> gate) {
        List<String> missing = new ArrayList<>();
        for (String key : List.of("dataRowsPresent", "toolSucceeded", "schemaResolved", "tableMatchesRequest", "tableScopedMetadata", "noExecutionFailure")) {
            if (!Boolean.TRUE.equals(gate.get(key))) {
                missing.add(key);
            }
        }
        return "semantic_gate_failed:" + String.join(",", missing);
    }

    private Map<String, Object> sqlDataMap(Map<String, Object> output) {
        Map<String, Object> data = asMap(output.get("data"));
        if (!data.isEmpty()) {
            return data;
        }
        if (output.containsKey("rows") || output.containsKey("columns") || output.containsKey("rowCount")) {
            return output;
        }
        return firstNonEmptyMap(output.get("structuredContent"), output.get("structured_content"), output.get("result"), output.get("payload"), output.get("body"), output.get("output"));
    }

    private List<Map<String, Object>> sqlColumnRows(Object value) {
        List<Map<String, Object>> rows = sqlColumnRows(value, 0);
        return rows == null ? List.of() : rows;
    }

    private List<Map<String, Object>> sqlColumnRows(Object value, int depth) {
        if (value == null || depth > 8) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = rowMaps(list);
            return looksLikeColumnMetadata(rows) ? rows : List.of();
        }
        Map<String, Object> map = asMap(value);
        if (map.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> directRows = rowMaps(map.get("rows"));
        if (looksLikeColumnMetadata(directRows)) {
            return directRows;
        }
        List<Map<String, Object>> metadataSearchRows = metadataSearchColumnRows(map);
        if (looksLikeColumnMetadata(metadataSearchRows)) {
            return metadataSearchRows;
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            List<Map<String, Object>> nested = sqlColumnRows(map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        Object content = map.get("content");
        if (content instanceof List<?> list) {
            for (Object item : list) {
                Object nestedValue = item instanceof Map<?, ?> itemMap
                    ? firstNonNull(itemMap.get("structuredContent"), itemMap.get("data"), itemMap.get("text"), itemMap.get("content"))
                    : item;
                List<Map<String, Object>> nested = sqlColumnRows(nestedValue, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return List.of();
    }

    private Map<String, Object> firstMetadataSearchResult(Map<String, Object> output) {
        return firstMetadataSearchResult(output, 0);
    }

    private Map<String, Object> firstMetadataSearchResult(Object value, int depth) {
        if (value == null || depth > 8) {
            return Map.of();
        }
        Map<String, Object> map = asMap(value);
        if (map.isEmpty()) {
            return Map.of();
        }
        Object results = firstNonNull(map.get("results"), map.get("items"), map.get("records"));
        if (results instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> result = asMap(item);
                if (!result.isEmpty() && !metadataSearchColumnRows(result).isEmpty()) {
                    return result;
                }
            }
            if (!list.isEmpty()) {
                Map<String, Object> first = asMap(list.get(0));
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            Map<String, Object> nested = firstMetadataSearchResult(map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> metadataSearchColumnRows(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> direct = normalizeMetadataSearchColumns(rowMaps(map.get("columns")));
        if (!direct.isEmpty()) {
            return direct;
        }
        Object results = firstNonNull(map.get("results"), map.get("items"), map.get("records"));
        if (results instanceof List<?> list) {
            for (Object item : list) {
                List<Map<String, Object>> rows = metadataSearchColumnRows(asMap(item));
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> normalizeMetadataSearchColumns(List<Map<String, Object>> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = firstNonBlank(value(column, "COLUMN_NAME", "column_name", "name", "columnName"), "");
            String type = firstNonBlank(value(column, "COLUMN_TYPE", "column_type", "columnType", "dataType", "DATA_TYPE", "data_type", "type"), "");
            if (blank(name) || blank(type)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("COLUMN_NAME", name);
            row.put("COLUMN_TYPE", type);
            row.put("IS_NULLABLE", nullableText(column));
            row.put("COLUMN_DEFAULT", firstNonBlank(value(column, "COLUMN_DEFAULT", "column_default", "defaultValue", "default"), ""));
            row.put("COLUMN_KEY", firstNonBlank(value(column, "COLUMN_KEY", "column_key", "columnKey", "key"), ""));
            row.put("EXTRA", firstNonBlank(value(column, "EXTRA", "extra"), ""));
            row.put("COLUMN_COMMENT", firstNonBlank(value(column, "COLUMN_COMMENT", "column_comment", "comment", "remarks"), ""));
            rows.add(row);
        }
        return rows;
    }

    private String nullableText(Map<String, Object> column) {
        String value = firstNonBlank(value(column, "IS_NULLABLE", "is_nullable", "nullable"), "");
        if ("true".equalsIgnoreCase(value)) {
            return "YES";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "NO";
        }
        return value;
    }

    private String metricRecommendation(List<Map<String, Object>> rows, String schema, String table) {
        List<String> stringColumns = new ArrayList<>();
        List<String> numericColumns = new ArrayList<>();
        List<String> timeColumns = new ArrayList<>();
        List<String> keyColumns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = firstNonBlank(value(row, "COLUMN_NAME", "column_name"), "");
            String type = firstNonBlank(value(row, "COLUMN_TYPE", "column_type"), "").toLowerCase();
            String key = firstNonBlank(value(row, "COLUMN_KEY", "column_key"), "");
            if (name.isBlank()) {
                continue;
            }
            if (!key.isBlank()) {
                keyColumns.add(name);
            }
            if (type.contains("char") || type.contains("text") || type.contains("enum")) {
                stringColumns.add(name);
            } else if (type.contains("int") || type.contains("decimal") || type.contains("numeric")
                || type.contains("double") || type.contains("float")) {
                numericColumns.add(name);
            } else if (type.contains("date") || type.contains("time")) {
                timeColumns.add(name);
            }
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 基于结构的统计建议\n\n");
        if (!keyColumns.isEmpty()) {
            markdown.append("- 键字段：").append(inlineColumns(keyColumns)).append("，适合做唯一性、重复率与关联键检查。\n");
        }
        if (!stringColumns.isEmpty()) {
            markdown.append("- 字符/枚举字段：").append(inlineColumns(stringColumns)).append("，适合做取值分布、空串率、Top N 统计。\n");
        }
        if (!numericColumns.isEmpty()) {
            markdown.append("- 数值字段：").append(inlineColumns(numericColumns)).append("，适合做最小值、最大值、均值、标准差、分位数。\n");
        }
        if (!timeColumns.isEmpty()) {
            markdown.append("- 时间字段：").append(inlineColumns(timeColumns)).append("，适合做时间跨度、最近更新时间、按日/月趋势。\n");
        }
        markdown.append("\n");
        markdown.append("可复核查询：\n\n");
        markdown.append("```sql\n");
        markdown.append("SELECT column_name, column_type, is_nullable, column_default, column_key, extra, column_comment\n");
        markdown.append("FROM information_schema.columns\n");
        markdown.append("WHERE table_schema = '").append(escapeSqlLiteral(schema)).append("'\n");
        markdown.append("  AND table_name = '").append(escapeSqlLiteral(table)).append("'\n");
        markdown.append("ORDER BY ordinal_position;\n");
        markdown.append("```\n");
        return markdown.toString().trim();
    }

    private boolean isSqlQueryExecute(String toolName) {
        return toolName != null && toolName.toLowerCase().contains("sql_query_execute");
    }

    private boolean isSqlMetadataSearch(String toolName) {
        return toolName != null && toolName.toLowerCase().contains("sql_metadata_search");
    }

    private boolean toolSucceeded(InterpretationPlanRuntime.StepExecution step) {
        if (step == null) {
            return false;
        }
        if (step.toolExecution() != null && step.toolExecution().output() != null) {
            return step.toolExecution().output().isSuccess();
        }
        return step.success();
    }

    private boolean isTableMetadataTemplate(String templateId) {
        if (templateId == null) {
            return false;
        }
        String normalized = templateId.trim().toUpperCase();
        return normalized.endsWith("_TABLE_METADATA") || "SQL_METADATA_SEARCH".equals(normalized);
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null && text.toLowerCase().contains(needle.toLowerCase());
    }

    private boolean looksLikeColumnMetadata(List<Map<String, Object>> rows) {
        return rows.stream().anyMatch(row -> !blank(value(row, "COLUMN_NAME", "column_name"))
            && !blank(value(row, "COLUMN_TYPE", "column_type")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Map<String, Object> firstNonEmptyMap(Object... values) {
        if (values == null) {
            return Map.of();
        }
        for (Object value : values) {
            Map<String, Object> map = asMap(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String defaultValue(Map<String, Object> row) {
        String value = firstNonBlank(value(row, "COLUMN_DEFAULT", "column_default"), "");
        return value.isBlank() ? "-" : "`" + value + "`";
    }

    private String value(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String qualifiedName(String schema, String table) {
        if (blank(schema)) {
            return emptyAsDash(table);
        }
        if (blank(table)) {
            return schema;
        }
        return schema + "." + table;
    }

    private String inlineColumns(List<String> columns) {
        return columns.stream()
            .filter(column -> column != null && !column.isBlank())
            .distinct()
            .limit(12)
            .map(column -> "`" + column + "`")
            .reduce((left, right) -> left + "、" + right)
            .orElse("-");
    }

    private String escapeCell(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    private String escapeInline(String value) {
        return value == null ? "" : value.replace("`", "'");
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String emptyAsDash(String value) {
        return blank(value) ? "-" : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim().replace("`", "").replace("\"", "").toLowerCase();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }

    record RenderedSqlMetadata(String markdown, Map<String, Object> metadata) {

        static RenderedSqlMetadata empty() {
            return new RenderedSqlMetadata("", Map.of());
        }
    }
}
