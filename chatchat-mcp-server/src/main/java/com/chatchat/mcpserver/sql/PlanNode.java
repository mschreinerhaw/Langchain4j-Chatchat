package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlanNode(
    String id,
    String type,
    String datasourceId,
    String schema,
    String table,
    String sqlFragment,
    List<String> dependencies,
    Map<String, Object> attributes
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("type", type);
        value.put("datasourceId", datasourceId);
        value.put("schema", schema);
        value.put("table", table);
        value.put("sqlFragment", sqlFragment);
        value.put("dependencies", dependencies == null ? List.of() : dependencies);
        value.put("attributes", attributes == null ? Map.of() : attributes);
        return value;
    }
}
