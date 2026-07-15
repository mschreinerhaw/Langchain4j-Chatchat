package com.chatchat.tools.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** A database-query workflow node independent from any persistence or MCP transport. */
public record SqlWorkflowNode(
    String code,
    String name,
    String description,
    String sql,
    int displayOrder,
    List<String> dependencies,
    List<SqlWorkflowParameterMapping> parameterMappings,
    Map<String, Object> staticParameters,
    String failureStrategy,
    String emptyResultStrategy,
    int timeoutSeconds,
    int maxRows
) {
    public SqlWorkflowNode {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        parameterMappings = parameterMappings == null ? List.of() : List.copyOf(parameterMappings);
        staticParameters = staticParameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(staticParameters));
        failureStrategy = failureStrategy == null ? "STOP" : failureStrategy;
        emptyResultStrategy = emptyResultStrategy == null ? "CONTINUE" : emptyResultStrategy;
    }
}
