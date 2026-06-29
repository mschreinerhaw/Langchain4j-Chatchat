package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.Map;

public record MetadataColumn(
    String datasourceId,
    String database,
    String schema,
    String table,
    String name,
    String dataType,
    String columnType,
    String columnKey,
    boolean nullable,
    Integer ordinalPosition
) {
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
        value.put("nullable", nullable);
        value.put("ordinalPosition", ordinalPosition);
        return value;
    }
}
