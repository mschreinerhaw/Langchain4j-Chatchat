package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (step == null || !isSqlQueryExecute(step.toolName())) {
            return RenderedSqlMetadata.empty();
        }
        Map<String, Object> output = asMap(step.output());
        if (output.isEmpty()) {
            return RenderedSqlMetadata.empty();
        }
        Map<String, Object> data = sqlDataMap(output);
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
            text(firstCandidate.get("database"))
        );
        String table = firstNonBlank(
            text(resolution.get("selectedTable")),
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name")),
            text(diagnostics.get("tableName")),
            text(firstCandidate.get("table"))
        );
        String datasourceName = firstNonBlank(
            text(target.get("name")),
            text(target.get("datasourceName")),
            text(datasource.get("name")),
            text(target.get("toolName")),
            text(datasource.get("toolName"))
        );
        String tableType = text(firstCandidate.get("tableType"));
        String tableRows = firstNonBlank(text(firstCandidate.get("tableRows")), text(data.get("tableRows")));
        String sql = text(operation.get("statement"));
        String requestedTable = firstNonBlank(
            text(executionContext.get("tableName")),
            text(executionContext.get("table_name")),
            text(diagnostics.get("tableName")),
            text(asMap(diagnostics.get("templateParameters")).get("tableName")),
            text(asMap(diagnostics.get("templateParameters")).get("table_name"))
        );
        String templateId = firstNonBlank(text(diagnostics.get("templateId")), text(output.get("templateId")), text(output.get("template")));
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
        metadata.put("source", "mcp_sql_query_execute_rows");
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
        return templateId != null && templateId.trim().toUpperCase().endsWith("_TABLE_METADATA");
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
