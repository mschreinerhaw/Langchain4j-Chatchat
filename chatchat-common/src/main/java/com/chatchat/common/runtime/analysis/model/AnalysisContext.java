package com.chatchat.common.runtime.analysis.model;

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
    public static final String AGENT_CAPABILITY_ATTRIBUTE = "runtime.agent.capability";
    public static final String EVIDENCE_BUNDLE_ATTRIBUTE = "runtime.analysis.evidenceBundle";

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

    public AnalysisContext withAttribute(String name, Object value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("attribute name is required");
        Map<String, Object> next = new LinkedHashMap<>(attributes);
        if (value == null) next.remove(name); else next.put(name, value);
        return new AnalysisContext(query, kernelScope, skillId, documentIds, documentTags, roles, intent, next);
    }

    public AnalysisExecutionMode executionMode() {
        return AnalysisExecutionMode.from(attributes.get(EXECUTION_MODE_ATTRIBUTE));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
