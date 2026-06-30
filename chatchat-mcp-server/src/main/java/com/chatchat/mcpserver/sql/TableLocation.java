package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.Map;

public record TableLocation(
    String datasourceId,
    String database,
    String schema,
    String table,
    String tableType,
    Long tableRows,
    String tableComment,
    String databaseComment,
    double score
) {
    public TableLocation(String datasourceId,
                         String database,
                         String schema,
                         String table,
                         String tableType,
                         Long tableRows,
                         double score) {
        this(datasourceId, database, schema, table, tableType, tableRows, null, null, score);
    }

    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("datasourceId", datasourceId);
        value.put("database", database);
        value.put("schema", schema);
        value.put("table", table);
        value.put("tableType", tableType);
        value.put("tableRows", tableRows);
        value.put("tableComment", tableComment);
        value.put("databaseComment", databaseComment);
        value.put("score", score);
        return value;
    }
}
