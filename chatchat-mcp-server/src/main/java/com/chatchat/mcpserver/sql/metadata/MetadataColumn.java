package com.chatchat.mcpserver.sql.metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public record MetadataColumn(
    String datasourceId,
    String database,
    String schema,
    String table,
    String name,
    String dataType,
    String columnType,
    String columnKey,
    String comment,
    boolean nullable,
    Integer ordinalPosition,
    List<String> partitionKeys
) {
    public MetadataColumn {
        partitionKeys = partitionKeys == null ? List.of() : List.copyOf(partitionKeys);
    }
    public MetadataColumn(String datasourceId, String database, String schema, String table, String name,
                          String dataType, String columnType, String columnKey, String comment, boolean nullable, Integer ordinalPosition) {
        this(datasourceId, database, schema, table, name, dataType, columnType, columnKey, comment, nullable, ordinalPosition, List.of());
    }
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("datasourceId", datasourceId);
        value.put("database", database);
        value.put("schema", schema);
        value.put("table", table);
        value.put("name", name);
        value.put("dataType", dataType);
        value.put("columnType", columnType);
        value.put("columnKey", columnKey);
        value.put("comment", comment);
        value.put("nullable", nullable);
        value.put("ordinalPosition", ordinalPosition);
        return value;
    }
}
