package com.chatchat.mcpserver.sql;

import java.util.List;
import java.util.Map;

public record SqlQueryResult(
    boolean success,
    String datasourceId,
    String datasourceName,
    String toolName,
    String environment,
    String sql,
    String normalizedSql,
    int timeoutSeconds,
    int maxRows,
    List<String> columns,
    List<Map<String, Object>> columnMetadata,
    List<Map<String, Object>> rows,
    int rowCount,
    boolean possiblyTruncated,
    long durationMs,
    String purpose,
    String sourceTaskId,
    String errorMessage
) {
    public SqlQueryResult {
        columns = columns == null ? List.of() : columns;
        columnMetadata = columnMetadata == null ? List.of() : columnMetadata;
        rows = rows == null ? List.of() : rows;
    }

    public SqlQueryResult(
        boolean success,
        String datasourceId,
        String datasourceName,
        String toolName,
        String environment,
        String sql,
        String normalizedSql,
        int timeoutSeconds,
        int maxRows,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean possiblyTruncated,
        long durationMs,
        String purpose,
        String sourceTaskId,
        String errorMessage
    ) {
        this(
            success,
            datasourceId,
            datasourceName,
            toolName,
            environment,
            sql,
            normalizedSql,
            timeoutSeconds,
            maxRows,
            columns,
            defaultColumnMetadata(columns),
            rows,
            rowCount,
            possiblyTruncated,
            durationMs,
            purpose,
            sourceTaskId,
            errorMessage
        );
    }

    private static List<Map<String, Object>> defaultColumnMetadata(List<String> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream()
            .map(column -> Map.<String, Object>of(
                "name", column,
                "label", column,
                "comment", ""
            ))
            .toList();
    }
}
