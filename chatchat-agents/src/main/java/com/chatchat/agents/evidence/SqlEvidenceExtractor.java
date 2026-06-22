package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlEvidenceExtractor {

    private static final Pattern SQL_HINT = Pattern.compile(
        "(?is)\\b(select|with|insert\\s+into|update|delete\\s+from|create\\s+table|alter\\s+table|drop\\s+table)\\b"
    );
    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "(?is)\\b(?:from|join|into|update|table)\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)?)"
    );
    private static final Pattern SELECT_COLUMNS_PATTERN = Pattern.compile("(?is)\\bselect\\s+(.*?)\\s+from\\s+");

    public boolean containsSql(String content) {
        return content != null && SQL_HINT.matcher(content).find();
    }

    public SqlEvidence extract(String content) {
        String raw = content == null ? "" : content.trim();
        String normalized = normalizeSql(raw);
        if (normalized.isBlank() || !containsSql(normalized)) {
            return new SqlEvidence(raw, normalized, SqlType.UNKNOWN, List.of(), List.of(), false);
        }
        SqlType type = sqlType(normalized);
        List<String> tables = tableNames(normalized);
        List<String> columns = columns(normalized);
        boolean complete = type == SqlType.DDL
            ? !tables.isEmpty()
            : type != SqlType.UNKNOWN && !tables.isEmpty();
        return new SqlEvidence(raw, normalized, type, tables, columns, complete);
    }

    private SqlType sqlType(String sql) {
        String normalized = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("with") || normalized.startsWith("select")) {
            return SqlType.SELECT;
        }
        if (normalized.startsWith("insert")) {
            return SqlType.INSERT;
        }
        if (normalized.startsWith("update")) {
            return SqlType.UPDATE;
        }
        if (normalized.startsWith("delete")) {
            return SqlType.DELETE;
        }
        if (normalized.startsWith("create") || normalized.startsWith("alter") || normalized.startsWith("drop")) {
            return SqlType.DDL;
        }
        return SqlType.UNKNOWN;
    }

    private List<String> tableNames(String sql) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            String value = stripIdentifier(matcher.group(1));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private List<String> columns(String sql) {
        Matcher matcher = SELECT_COLUMNS_PATTERN.matcher(sql == null ? "" : sql);
        if (!matcher.find()) {
            return List.of();
        }
        String expression = matcher.group(1);
        if (expression == null || expression.isBlank() || expression.contains("*")) {
            return List.of("*");
        }
        List<String> columns = new ArrayList<>();
        for (String part : expression.split(",")) {
            String column = normalizeColumn(part);
            if (!column.isBlank() && !columns.contains(column)) {
                columns.add(column);
            }
        }
        return List.copyOf(columns);
    }

    private String normalizeColumn(String value) {
        if (value == null) {
            return "";
        }
        String column = value.replaceAll("(?i)\\s+as\\s+\\w+$", "")
            .replaceAll("\\s+\\w+$", "")
            .trim();
        int dot = column.lastIndexOf('.');
        if (dot >= 0 && dot < column.length() - 1 && column.indexOf('(') < 0) {
            column = column.substring(dot + 1);
        }
        return column.replaceAll("[`\"\\[\\]]", "").trim();
    }

    private String stripIdentifier(String value) {
        return value == null ? "" : value.replaceAll("[`\"\\[\\]]", "").trim();
    }

    private String normalizeSql(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}
