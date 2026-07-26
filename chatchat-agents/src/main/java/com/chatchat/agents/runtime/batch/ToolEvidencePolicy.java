package com.chatchat.agents.runtime.batch;

import java.util.List;

/**
 * Template-owned evidence semantics copied into an executable child call.
 */
public record ToolEvidencePolicy(
    String purpose,
    Boolean healthCapability,
    List<String> requiredMetrics,
    String timeSemantics,
    List<String> requiresContext,
    Integer freshnessMaxAgeSeconds
) {
    public ToolEvidencePolicy {
        purpose = normalizedText(purpose);
        requiredMetrics = normalizedList(requiredMetrics);
        timeSemantics = normalizedText(timeSemantics);
        requiresContext = normalizedList(requiresContext);
        if (freshnessMaxAgeSeconds != null && freshnessMaxAgeSeconds < 0) {
            freshnessMaxAgeSeconds = null;
        }
    }

    public static ToolEvidencePolicy empty() {
        return new ToolEvidencePolicy(null, null, List.of(), null, List.of(), null);
    }

    private static String normalizedText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalizedList(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}
