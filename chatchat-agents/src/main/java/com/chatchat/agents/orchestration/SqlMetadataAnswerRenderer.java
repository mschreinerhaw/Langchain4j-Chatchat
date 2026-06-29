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

    String render(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            return "";
        }
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            String rendered = renderStep(step);
            if (!rendered.isBlank()) {
                return rendered;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String renderStep(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || !step.success() || !isSqlQueryExecute(step.toolName())) {
            return "";
        }
        Map<String, Object> output = asMap(step.output());
        if (output.isEmpty()) {
            return "";
        }
        Map<String, Object> data = asMap(output.get("data"));
        List<Map<String, Object>> rows = rowMaps(data.get("rows"));
        if (rows.isEmpty() || !looksLikeColumnMetadata(rows)) {
            return "";
        }
        Map<String, Object> operation = asMap(output.get("operation"));
        Map<String, Object> diagnostics = asMap(operation.get("diagnostics"));
        Map<String, Object> resolution = asMap(diagnostics.get("tableResolution"));
        Map<String, Object> target = asMap(output.get("target"));
        Map<String, Object> firstCandidate = firstMap(resolution.get("candidates"));
        String schema = firstNonBlank(text(resolution.get("selectedSchema")), text(diagnostics.get("schemaName")), text(firstCandidate.get("schema")));
        String table = firstNonBlank(text(resolution.get("selectedTable")), text(diagnostics.get("tableName")), text(firstCandidate.get("table")));
        String datasourceName = firstNonBlank(text(target.get("name")), text(target.get("toolName")));
        String tableType = text(firstCandidate.get("tableType"));
        String tableRows = firstNonBlank(text(firstCandidate.get("tableRows")), text(data.get("tableRows")));
        String sql = text(operation.get("statement"));

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
        return markdown.toString().trim();
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

    private boolean blank(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }
}
