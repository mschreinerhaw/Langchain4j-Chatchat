package com.chatchat.mcpserver.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticOperationNode(
    String id,
    String operation,
    String targetLevel,
    String dialect,
    List<String> targets,
    Map<String, Object> attributes
) {
    public Map<String, Object> toDiagnostic() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("operation", operation);
        value.put("targetLevel", targetLevel);
        value.put("dialect", dialect);
        value.put("targets", targets == null ? List.of() : targets);
        value.put("attributes", attributes == null ? Map.of() : attributes);
        return value;
    }
}
