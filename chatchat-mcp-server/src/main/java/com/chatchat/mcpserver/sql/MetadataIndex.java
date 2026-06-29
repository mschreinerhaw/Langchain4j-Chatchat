package com.chatchat.mcpserver.sql;

import java.util.List;
import java.util.Map;

public record MetadataIndex(
    String datasourceId,
    String databaseType,
    List<TableLocation> tables,
    Map<String, List<TableLocation>> tableIndex,
    Map<String, List<String>> schemaTables,
    Map<String, List<MetadataColumn>> tableColumns,
    List<String> datasourceSchemas,
    long refreshedAtMs,
    boolean cacheHit,
    String error
) {
    public static MetadataIndex failed(String datasourceId, String databaseType, String error) {
        return new MetadataIndex(
            datasourceId,
            databaseType,
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            System.currentTimeMillis(),
            false,
            error
        );
    }
}
