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
    double score
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("datasourceId", datasourceId);
        value.put("database", database);
        value.put("schema", schema);
        value.put("table", table);
        value.put("tableType", tableType);
        value.put("tableRows", tableRows);
        value.put("score", score);
        return value;
    }
}
