package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QueryFeedback(
    boolean emptyResult,
    String usedSchema,
    List<String> alternativeSchemas,
    String action,
    String reason
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("emptyResult", emptyResult);
        value.put("usedSchema", usedSchema);
        value.put("alternativeSchemas", alternativeSchemas == null ? List.of() : alternativeSchemas);
        value.put("action", action);
        value.put("reason", reason);
        return value;
    }
}
