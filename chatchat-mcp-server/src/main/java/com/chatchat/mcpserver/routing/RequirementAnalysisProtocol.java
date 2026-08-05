package com.chatchat.mcpserver.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared, domain-neutral protocol normalization for model-produced requirement decompositions. */
public final class RequirementAnalysisProtocol {

    public static final int MAX_REQUIREMENTS = 20;
    private static final List<String> LOGICAL_CONTEXT_FIELDS = List.of(
        "env", "environment", "assetName", "asset_name", "service", "category", "labels");

    private RequirementAnalysisProtocol() {
    }

    public static Map<String, Object> inputProperties() {
        return Map.of(
            "schemaVersion", Map.of("type", "string"),
            "query", Map.of("type", "string", "description",
                "Single requirement shorthand. Used when requirements is omitted or empty."),
            "goal", Map.of("type", "string", "description",
                "Overall goal. Optional when a requirement intent or description is supplied."),
            "requirements", Map.of(
                "type", "array",
                "maxItems", MAX_REQUIREMENTS,
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "intent", Map.of("type", "string", "description", "Alias for description"),
                        "requiredOutputs", Map.of("type", "array", "items", Map.of("type", "string")),
                        "constraints", Map.of("type", "array", "items", Map.of("type", "string")),
                        "dependsOn", Map.of("type", "array", "items", Map.of("type", "string"))
                    )
                )
            ),
            "context", Map.of(
                "type", "object",
                "description", "Optional logical routing context",
                "additionalProperties", true
            ),
            "excludeTemplateIds", Map.of("type", "array", "items", Map.of("type", "string")),
            "limitPerRequirement", Map.of("type", "integer", "minimum", 1, "maximum", 10)
        );
    }

    @SuppressWarnings("unchecked")
    public static NormalizedRequest normalize(Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        Object rawRequirements = input.get("requirements");
        List<?> requirements;
        if (rawRequirements == null || (rawRequirements instanceof List<?> list && list.isEmpty())) {
            String query = firstText(input.get("query"), input.get("goal"));
            if (query.isBlank()) {
                throw new IllegalArgumentException("requirements or query must contain at least one requirement");
            }
            requirements = List.of(Map.of("intent", query));
        } else if (rawRequirements instanceof List<?> list) {
            requirements = list;
        } else {
            throw new IllegalArgumentException("requirements must be an array");
        }
        if (requirements.size() > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException("requirements exceeds maximum " + MAX_REQUIREMENTS);
        }
        String goal = text(input.get("goal"));
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (int index = 0; index < requirements.size(); index++) {
            Object item = requirements.get(index);
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("each requirement must be an object");
            }
            Map<String, Object> requirement = new LinkedHashMap<>((Map<String, Object>) raw);
            String description = firstText(requirement.get("description"), requirement.get("intent"));
            if (description.isBlank()) {
                throw new IllegalArgumentException("requirement description or intent is required");
            }
            String id = text(requirement.get("id"));
            if (id.isBlank()) id = "requirement_" + (index + 1);
            if (goal.isBlank()) goal = description;
            requirement.put("id", id);
            requirement.put("description", description);
            normalized.add(requirement);
        }
        Map<String, Object> context = input.get("context") instanceof Map<?, ?> rawContext
            ? new LinkedHashMap<>((Map<String, Object>) rawContext)
            : Map.of();
        return new NormalizedRequest(
            goal,
            List.copyOf(normalized),
            integer(input.get("limitPerRequirement"), 5, 1, 10),
            input.get("excludeTemplateIds"),
            context
        );
    }

    public static Map<String, Object> discoveryFilters(Map<String, Object> requirement,
                                                        String goal,
                                                        Map<String, Object> context) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("intent", requirement.get("description"));
        filters.put("goal", goal);
        filters.put("keywords", requirement.getOrDefault("requiredOutputs", List.of()));
        if (requirement.get("constraints") != null) {
            filters.put("retrievalSignals", requirement.get("constraints"));
        }
        if (context != null) {
            LOGICAL_CONTEXT_FIELDS.forEach(key -> {
                if (context.get(key) != null) filters.put(key, context.get(key));
            });
        }
        return filters;
    }

    public static int integer(Object value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String firstText(Object... values) {
        if (values != null) {
            for (Object value : values) {
                String text = text(value);
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record NormalizedRequest(String goal,
                                    List<Map<String, Object>> requirements,
                                    int limitPerRequirement,
                                    Object excludeTemplateIds,
                                    Map<String, Object> context) {
    }
}
