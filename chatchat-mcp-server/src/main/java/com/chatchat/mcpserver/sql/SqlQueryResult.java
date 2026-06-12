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
    List<Map<String, Object>> rows,
    int rowCount,
    boolean possiblyTruncated,
    long durationMs,
    String purpose,
    String sourceTaskId,
    String errorMessage
) {
}
