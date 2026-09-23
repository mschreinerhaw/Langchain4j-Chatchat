package com.chatchat.common.runtime.analysis.model;

import java.util.List;
import java.util.Map;

public record AnalysisScope(
    String tenantId,
    String userId,
    List<String> roles,
    List<String> resourceIds,
    Map<String, Object> constraints
) {
    public AnalysisScope {
        roles = roles == null ? List.of() : List.copyOf(roles);
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }
}
