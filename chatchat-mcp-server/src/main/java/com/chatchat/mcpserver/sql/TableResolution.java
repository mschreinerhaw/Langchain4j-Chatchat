package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TableResolution(
    String datasourceId,
    String databaseType,
    String requestedTable,
    String preferredSchema,
    String selectedSchema,
    String selectedTable,
    String reason,
    double confidence,
    List<TableLocation> candidates,
    long durationMs,
    boolean cacheHit,
    String error
) {
    public static TableResolution unsupported(String datasourceId, String tableName, String preferredSchema,
                                              String databaseType) {
        return new TableResolution(
            datasourceId,
            databaseType,
            tableName,
            preferredSchema,
            null,
            tableName,
            "unsupported_database_type",
            0.0,
            List.of(),
            0,
            false,
            null
        );
    }

    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "table_resolution.v3");
        value.put("strategy", "metadata_index_semantic_ranking_history_fusion");
        value.put("datasourceId", datasourceId);
        value.put("databaseType", databaseType);
        value.put("requestedTable", requestedTable);
        value.put("preferredSchema", preferredSchema);
        value.put("selectedSchema", selectedSchema);
        value.put("selectedTable", selectedTable);
        value.put("reason", reason);
        value.put("confidence", confidence);
        value.put("candidateCount", candidates == null ? 0 : candidates.size());
        value.put("candidates", candidates == null
            ? List.of()
            : candidates.stream().limit(20).map(TableLocation::toDiagnostic).toList());
        value.put("durationMs", durationMs);
        value.put("cacheHit", cacheHit);
        value.put("error", error);
        return value;
    }
}
