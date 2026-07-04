package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SqlScriptResult(
    boolean success,
    String datasourceId,
    String datasourceName,
    String toolName,
    String environment,
    String script,
    int timeoutSeconds,
    int maxRowsPerStatement,
    int statementCount,
    List<SqlScriptStatementResult> results,
    long durationMs,
    String purpose,
    String sourceTaskId,
    String errorMessage,
    Map<String, Object> diagnostics
) {
    public SqlScriptResult {
        results = results == null ? List.of() : results;
        diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
    }
}
