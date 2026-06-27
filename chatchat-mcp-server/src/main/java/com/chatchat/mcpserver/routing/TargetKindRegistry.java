package com.chatchat.mcpserver.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TargetKindRegistry {

    public static final String FILTERS_SCHEMA_VERSION = "target_filters.v1";
    public static final double MIN_CONFIDENCE = 0.6;
    public static final String DECISION_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    private static final Map<String, TargetDefinition> DEFINITIONS = definitions();
    private static final Map<String, String> ASSET_TYPE_TO_TARGET_KIND = assetTypeToTargetKind();

    public Resolution resolveForTool(String toolName,
                                     Object explicitAssetType,
                                     Map<String, Object> arguments,
                                     Map<String, Object> filters) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String finalDecision = normalize(firstValue(args, "finalDecision", "targetKind"));
        String assetType = normalize(explicitAssetType);
        List<Candidate> candidates = candidates(args);
        if (finalDecision == null && assetType == null) {
            throw new TargetKindException(requiredMessage(toolName), details("MISSING_TARGET_KIND", requiredMessage(toolName)));
        }
        TargetDefinition definition = definitionFor(finalDecision, assetType);
        assertAllowed(toolName, definition);
        if (assetType != null && !assetType.equals(definition.assetType())) {
            String message = "targetKind=" + definition.targetKind() + " maps to assetType=" + definition.assetType()
                + ", but request assetType=" + assetType;
            throw new TargetKindException(message, details("TARGET_KIND_ASSET_TYPE_CONFLICT", message));
        }
        double confidence = confidence(args, candidates);
        List<Candidate> resolvedCandidates = candidates.isEmpty()
            ? List.of(candidate(definition, confidence, true, List.of("explicit_target")))
            : candidates;
        boolean reviewRequired = confidence < MIN_CONFIDENCE;
        String decision = reviewRequired ? DECISION_REVIEW_REQUIRED : "ACCEPTED";
        return new Resolution(
            definition,
            definition.targetKind(),
            confidence,
            reviewRequired,
            FILTERS_SCHEMA_VERSION,
            decision,
            resolvedCandidates,
            trace(args)
        );
    }

    public String targetKindForAssetType(String assetType) {
        String normalized = normalize(assetType);
        return ASSET_TYPE_TO_TARGET_KIND.getOrDefault(normalized, normalized);
    }

    private TargetDefinition definitionFor(String targetKind, String assetType) {
        if (targetKind != null) {
            TargetDefinition definition = DEFINITIONS.get(targetKind);
            if (definition == null) {
                String message = "Unsupported targetKind: " + targetKind;
                throw new TargetKindException(message, details("UNSUPPORTED_TARGET_KIND", message));
            }
            return definition;
        }
        String mappedTargetKind = ASSET_TYPE_TO_TARGET_KIND.get(assetType);
        if (mappedTargetKind == null) {
            String message = "Unsupported assetType: " + assetType;
            throw new TargetKindException(message, details("UNSUPPORTED_ASSET_TYPE", message));
        }
        return DEFINITIONS.get(mappedTargetKind);
    }

    private void assertAllowed(String toolName, TargetDefinition definition) {
        String normalizedTool = normalize(toolName);
        if ("template_query".equals(normalizedTool) && "document".equals(definition.targetKind())) {
            String message = "targetKind=document is not allowed for template_query; use document_search";
            throw new TargetKindException(message, details("TARGET_KIND_NOT_ALLOWED", message));
        }
        if ("asset_query".equals(normalizedTool) && "document".equals(definition.targetKind())) {
            String message = "targetKind=document is not allowed for asset_query; use document_search";
            throw new TargetKindException(message, details("TARGET_KIND_NOT_ALLOWED", message));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Candidate> candidates(Map<String, Object> args) {
        Object value = args.get("candidates");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String targetKind = normalize(map.get("targetKind"));
            TargetDefinition definition = targetKind == null ? null : DEFINITIONS.get(targetKind);
            double confidence = number(map.get("confidence"), 0.0);
            if (definition == null) {
                candidates.add(new Candidate(
                    targetKind,
                    null,
                    confidence,
                    false,
                    List.of("unsupported_target_kind"),
                    0,
                    0.0,
                    0.0
                ));
            } else {
                candidates.add(candidate(definition, confidence, true, List.of("schema_match", "tool_permission")));
            }
        }
        return candidates;
    }

    private Candidate candidate(TargetDefinition definition, double confidence, boolean feasible, List<String> reasons) {
        double score = Math.max(0.0, Math.min(1.0, confidence));
        return new Candidate(
            definition.targetKind(),
            definition.assetType(),
            confidence,
            feasible,
            reasons,
            definition.latencyEstimateMs(),
            definition.historicalSuccessRate(),
            score
        );
    }

    private double confidence(Map<String, Object> args, List<Candidate> candidates) {
        Object value = firstValue(args, "confidence");
        if (value != null) {
            return number(value, 1.0);
        }
        return candidates.stream()
            .filter(Candidate::feasible)
            .mapToDouble(Candidate::confidence)
            .max()
            .orElse(1.0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> trace(Map<String, Object> args) {
        Object value = args.get("trace");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Object firstValue(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String requiredMessage(String toolName) {
        String normalized = normalize(toolName);
        return (normalized == null ? "query" : normalized) + " requires finalDecision or explicit targetKind";
    }

    private Map<String, Object> details(String code, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("message", message);
        details.put("filtersSchemaVersion", FILTERS_SCHEMA_VERSION);
        details.put("allowedTargetKinds", DEFINITIONS.keySet().stream().toList());
        return details;
    }

    private String normalize(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private double number(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Map<String, TargetDefinition> definitions() {
        Map<String, TargetDefinition> definitions = new LinkedHashMap<>();
        definitions.put("host", new TargetDefinition("host", "ssh_host", 250, 0.92));
        definitions.put("database", new TargetDefinition("database", "sql_datasource", 320, 0.9));
        definitions.put("http", new TargetDefinition("http", "http_endpoint", 180, 0.88));
        definitions.put("business_database_query", new TargetDefinition("business_database_query", "database_query", 200, 0.9));
        definitions.put("document", new TargetDefinition("document", "document_search", 150, 0.9));
        return definitions;
    }

    private static Map<String, String> assetTypeToTargetKind() {
        Map<String, String> map = new LinkedHashMap<>();
        DEFINITIONS.values().forEach(definition -> map.put(definition.assetType(), definition.targetKind()));
        return map;
    }

    public record TargetDefinition(String targetKind,
                                   String assetType,
                                   long latencyEstimateMs,
                                   double historicalSuccessRate) {
    }

    public record Candidate(String targetKind,
                            String assetType,
                            double confidence,
                            boolean feasible,
                            List<String> feasibilityReasons,
                            long latencyEstimateMs,
                            double historicalSuccessRate,
                            double score) {
    }

    public record Resolution(TargetDefinition definition,
                             String finalDecision,
                             double confidence,
                             boolean reviewRequired,
                             String filtersSchemaVersion,
                             String decision,
                             List<Candidate> candidates,
                             Map<String, Object> trace) {
    }

    public static class TargetKindException extends IllegalArgumentException {

        private final Map<String, Object> details;

        public TargetKindException(String message, Map<String, Object> details) {
            super(message);
            this.details = details == null ? Map.of() : details;
        }

        public Map<String, Object> details() {
            return details;
        }
    }
}
