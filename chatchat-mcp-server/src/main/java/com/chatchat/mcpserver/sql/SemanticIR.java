package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticIR(
    String schemaVersion,
    SemanticOperationNode root,
    List<Map<String, Object>> constraints,
    Map<String, Object> context
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("root", root == null ? null : root.toDiagnostic());
        value.put("constraints", constraints == null ? List.of() : constraints);
        value.put("context", context == null ? Map.of() : context);
        return value;
    }
}
