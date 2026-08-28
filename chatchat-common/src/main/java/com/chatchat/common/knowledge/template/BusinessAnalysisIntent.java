package com.chatchat.common.knowledge.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Query-to-business-intent projection produced before template execution. */
public record BusinessAnalysisIntent(
    String businessGoal,
    String analysisSubject,
    List<String> coreEntities,
    List<String> metrics,
    List<String> dimensions,
    List<String> analysisFocus,
    String timeScope,
    List<String> expectedRelationships
) {
    public BusinessAnalysisIntent {
        businessGoal = clean(businessGoal);
        analysisSubject = clean(analysisSubject);
        coreEntities = strings(coreEntities);
        metrics = strings(metrics);
        dimensions = strings(dimensions);
        analysisFocus = strings(analysisFocus);
        timeScope = clean(timeScope);
        expectedRelationships = strings(expectedRelationships);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "businessGoal", businessGoal);
        put(value, "analysisSubject", analysisSubject);
        value.put("coreEntities", coreEntities);
        value.put("metrics", metrics);
        value.put("dimensions", dimensions);
        value.put("analysisFocus", analysisFocus);
        put(value, "timeScope", timeScope);
        value.put("expectedRelationships", expectedRelationships);
        return Collections.unmodifiableMap(value);
    }

    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null) target.put(key, value);
    }
}
