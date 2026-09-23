package com.chatchat.common.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AnalysisContext(
    String query,
    KernelDataScope kernelScope,
    String skillId,
    List<String> documentIds,
    List<String> documentTags,
    List<String> roles,
    AnalysisIntent intent,
    Map<String, Object> attributes
) {
    public static final String EXECUTION_MODE_ATTRIBUTE = "runtime.analysis.executionMode";

    public AnalysisContext {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("analysis query is required");
        if (kernelScope == null) throw new IllegalArgumentException("kernel scope is required");
        query = query.trim();
        skillId = skillId == null ? "" : skillId.trim();
        documentIds = clean(documentIds);
        documentTags = clean(documentTags);
        roles = clean(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public AnalysisContext withIntent(AnalysisIntent value) {
        return new AnalysisContext(query, kernelScope, skillId, documentIds, documentTags, roles, value, attributes);
    }

    public AnalysisExecutionMode executionMode() {
        return AnalysisExecutionMode.from(attributes.get(EXECUTION_MODE_ATTRIBUTE));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
