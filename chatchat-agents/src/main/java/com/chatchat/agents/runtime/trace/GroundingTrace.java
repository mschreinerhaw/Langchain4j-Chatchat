package com.chatchat.agents.runtime.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GroundingTrace(
    String status,
    List<Map<String, Object>> availableCitations,
    List<Map<String, Object>> usedCitations,
    List<String> missingInfo,
    List<String> unsupportedCitations
) {

    public GroundingTrace {
        availableCitations = copyMaps(availableCitations);
        usedCitations = copyMaps(usedCitations);
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
        unsupportedCitations = unsupportedCitations == null ? List.of() : List.copyOf(unsupportedCitations);
    }

    private static List<Map<String, Object>> copyMaps(List<Map<String, Object>> values) {
        return values == null
            ? List.of()
            : values.stream()
                .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                .toList();
    }
}
