package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.Map;

public record TableOccurrence(
    String datasourceId,
    String schema,
    String table,
    int queryFrequency,
    long lastUsedTime
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("datasourceId", datasourceId);
        value.put("schema", schema);
        value.put("table", table);
        value.put("queryFrequency", queryFrequency);
        value.put("lastUsedTime", lastUsedTime);
        return value;
    }
}
