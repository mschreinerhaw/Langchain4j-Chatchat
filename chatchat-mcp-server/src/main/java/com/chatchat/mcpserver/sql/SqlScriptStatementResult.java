package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SqlScriptStatementResult(
    int statementIndex,
    boolean success,
    String sql,
    List<String> columns,
    List<Map<String, Object>> columnMetadata,
    List<Map<String, Object>> rows,
    int rowCount,
    boolean possiblyTruncated,
    long durationMs,
    String errorMessage,
    Map<String, Object> diagnostics
) {
    public SqlScriptStatementResult {
        columns = columns == null ? List.of() : columns;
        columnMetadata = columnMetadata == null ? List.of() : columnMetadata;
        rows = rows == null ? List.of() : rows;
        diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
    }
}
