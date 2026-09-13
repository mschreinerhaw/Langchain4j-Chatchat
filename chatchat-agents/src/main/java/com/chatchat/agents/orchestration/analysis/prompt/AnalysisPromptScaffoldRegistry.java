package com.chatchat.agents.orchestration.analysis.prompt;

import java.util.*;

/** Loads optional guidance only after the planning stage has selected an analysis type. */
final class AnalysisPromptScaffoldRegistry {
    private AnalysisPromptScaffoldRegistry() { }

    static String normalize(Object type) {
        String value = type == null ? "GENERIC" : type.toString().trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : "GENERIC";
    }

    static Map<String, Object> apply(Map<String, Object> planned, Object selectedType,
                                    List<DomainAnalysisProfileProvider.Profile> profiles) {
        String type = normalize(selectedType);
        var profile = profiles.stream().filter(item -> item.analysisType().equals(type)).findFirst().orElse(null);
        Map<String, Object> scaffold = profile == null || "GENERIC".equals(type) ? Map.of() : profile.guidance();
        Map<String, Object> result = new LinkedHashMap<>(planned);
        result.remove("domainFocus");
        result.remove("domainProfileRevision");
        result.remove("output");
        result.remove("sectionTitles");
        result.put("analysisType", scaffold.isEmpty() ? "GENERIC" : type);
        if (scaffold.isEmpty()) return result;
        for (String key : List.of("focus")) {
            if (!(result.get(key) instanceof List<?> values) || values.isEmpty()) result.put(key, scaffold.get(key));
        }
        // Domain profiles may guide analysis, but must never prescribe the final
        // report's section inventory or localized headings.
        result.put("domainFocus", scaffold.getOrDefault("focus", List.of()));
        if (!(result.get("methodology") instanceof List<?> methods) || methods.isEmpty()) {
            result.put("methodology", scaffold.getOrDefault("methodology", List.of()));
        }
        result.put("domainProfileRevision", profile.revision());
        return result;
    }
}
