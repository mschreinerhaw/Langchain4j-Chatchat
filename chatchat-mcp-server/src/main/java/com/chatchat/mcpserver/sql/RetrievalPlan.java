package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RetrievalPlan(
    String schemaVersion,
    boolean retrievalNeeded,
    String retrievalType,
    String priority,
    String reason,
    String query,
    List<String> triggers,
    Map<String, Object> attributes
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("retrievalNeeded", retrievalNeeded);
        value.put("retrievalType", retrievalType);
        value.put("priority", priority);
        value.put("reason", reason);
        value.put("query", query);
        value.put("triggers", triggers == null ? List.of() : triggers);
        value.put("attributes", attributes == null ? Map.of() : attributes);
        return value;
    }
}
