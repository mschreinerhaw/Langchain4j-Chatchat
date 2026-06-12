package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SqlQueryExecuteService {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlSafetyService safetyService;
    private final SqlTemplateService templateService;
    private final InvocationAuditService auditService;
    private final ObjectMapper objectMapper;

    public SqlQueryResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        SqlDatasourceConfig datasource = null;
        String normalizedSql = null;
        SqlQueryResult result;
        int timeoutSeconds = 30;
        int maxRows = 1000;
        try {
            datasource = datasourceConfigService.getEnabled(text(request, "datasourceId"));
            timeoutSeconds = normalizeTimeout(request.get("timeoutSeconds"), datasource.getDefaultTimeoutSeconds());
            maxRows = normalizeMaxRows(request.get("maxRows"), datasource.getDefaultMaxRows());
            String sql = resolveSql(request);
            normalizedSql = safetyService.validateAndNormalize(sql, maxRows);
            validateAllowedTables(datasource, normalizedSql);
            result = query(datasource, sql, normalizedSql, timeoutSeconds, maxRows,
                text(request, "purpose"), text(request, "sourceTaskId"), startedAt);
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            result = new SqlQueryResult(
                false,
                datasource == null ? text(request, "datasourceId") : datasource.getId(),
                datasource == null ? null : datasource.getName(),
                datasource == null ? null : datasource.getToolName(),
                datasource == null ? null : datasource.getEnvironment(),
                text(request, "sql"),
                normalizedSql,
                timeoutSeconds,
                maxRows,
                List.of(),
                List.of(),
                0,
                false,
                durationMs,
                text(request, "purpose"),
                text(request, "sourceTaskId"),
                ex.getMessage()
            );
        }
        auditService.recordSqlQueryCall(datasource, result);
        return result;
    }

    public SqlQueryResult execute(SqlDatasourceConfig datasource, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        request.put("datasourceId", datasource.getId());
        return execute(request);
    }

    private String resolveSql(Map<String, Object> request) {
        String sql = text(request, "sql");
        String template = text(request, "template");
        if (sql != null && !sql.isBlank()) {
            return sql;
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Either sql or template is required");
        }
        return templateService.render(template, mapValue(request.get("parameters")));
    }

    private SqlQueryResult query(SqlDatasourceConfig datasource, String originalSql, String sql,
                                 int timeoutSeconds, int maxRows, String purpose,
                                 String sourceTaskId, long startedAt) throws Exception {
        if (datasource.getDriverClass() != null && !datasource.getDriverClass().isBlank()) {
            Class.forName(datasource.getDriverClass().trim());
        }
        try (Connection connection = DriverManager.getConnection(
            datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columns = columns(metaData);
                List<Map<String, Object>> rows = new ArrayList<>();
                Set<String> sensitiveFields = sensitiveFields(datasource);
                boolean maskAll = matchesSensitiveTable(datasource, sql);
                while (resultSet.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metaData.getColumnCount(); index++) {
                        String column = columns.get(index - 1);
                        Object value = resultSet.getObject(index);
                        row.put(column, maskAll || shouldMask(column, sensitiveFields) ? "***" : value);
                    }
                    rows.add(row);
                }
                long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                return new SqlQueryResult(
                    true,
                    datasource.getId(),
                    datasource.getName(),
                    datasource.getToolName(),
                    datasource.getEnvironment(),
                    originalSql,
                    sql,
                    timeoutSeconds,
                    maxRows,
                    columns,
                    rows,
                    rows.size(),
                    rows.size() >= maxRows,
                    durationMs,
                    purpose,
                    sourceTaskId,
                    null
                );
            }
        }
    }

    private List<String> columns(ResultSetMetaData metaData) throws Exception {
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String label = metaData.getColumnLabel(index);
            columns.add(label == null || label.isBlank() ? metaData.getColumnName(index) : label);
        }
        return columns;
    }

    private boolean shouldMask(String column, Set<String> sensitiveFields) {
        String normalized = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return sensitiveFields.contains(normalized)
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("phone")
            || normalized.contains("mobile")
            || normalized.contains("id_card");
    }

    private Set<String> sensitiveFields(SqlDatasourceConfig datasource) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add("password");
        fields.add("token");
        fields.add("secret");
        fields.add("mobile");
        fields.add("phone");
        fields.add("id_card");
        String json = datasource.getSensitiveFieldsJson();
        if (json == null || json.isBlank()) {
            return fields;
        }
        try {
            List<String> configured = objectMapper.readValue(json, new TypeReference<>() {});
            configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(fields::add);
        } catch (Exception ignored) {
            return fields;
        }
        return fields;
    }

    private boolean matchesSensitiveTable(SqlDatasourceConfig datasource, String sql) {
        String json = datasource.getSensitiveTablesJson();
        if (json == null || json.isBlank() || sql == null) {
            return false;
        }
        try {
            List<String> tables = objectMapper.readValue(json, new TypeReference<>() {});
            String normalizedSql = sql.toLowerCase(Locale.ROOT);
            return tables.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(table -> normalizedSql.matches("(?s).*\\b" + Pattern.quote(table) + "\\b.*"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void validateAllowedTables(SqlDatasourceConfig datasource, String sql) {
        String json = datasource.getAllowedTablesJson();
        if (json == null || json.isBlank() || sql == null) {
            return;
        }
        try {
            List<String> configured = objectMapper.readValue(json, new TypeReference<>() {});
            Set<String> allowed = configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
            if (allowed.isEmpty()) {
                return;
            }
            Pattern tablePattern = Pattern.compile("(?i)\\b(from|join)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?)");
            Matcher matcher = tablePattern.matcher(sql);
            while (matcher.find()) {
                String table = matcher.group(2).toLowerCase(Locale.ROOT);
                if (!allowed.contains(table)) {
                    throw new IllegalArgumentException("SQL table is not allowed for this datasource: " + table);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("allowedTables config is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int normalizeTimeout(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 60));
        }
        return Math.max(1, Math.min(fallback, 60));
    }

    private int normalizeMaxRows(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 5000));
        }
        return Math.max(1, Math.min(fallback, 5000));
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }
}
