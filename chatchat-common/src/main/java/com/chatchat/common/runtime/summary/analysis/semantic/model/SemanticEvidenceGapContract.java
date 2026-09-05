package com.chatchat.common.runtime.summary.analysis.semantic.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, domain-neutral request produced when a candidate claim cannot be admitted. */
public final class SemanticEvidenceGapContract {

    public static final String SCHEMA_VERSION = "semantic_evidence_gap.v1";

    private SemanticEvidenceGapContract() {
    }

    public enum Route {
        RETRIEVE_MORE,
        REPLAN,
        ANALYZE_WITH_LIMITATIONS
    }

    public record Gap(
        String gapId,
        Route route,
        Set<String> rejectionCodes,
        String requiredCapabilityId,
        SemanticOperation requiredOperation,
        Set<String> requiredFields,
        String requiredUnit,
        String requiredGrain,
        String requiredTimeScope,
        String requiredPopulationScope,
        Set<String> basedOnEvidenceReferences
    ) {
        public Gap {
            rejectionCodes = texts(rejectionCodes);
            requiredCapabilityId = text(requiredCapabilityId);
            requiredFields = texts(requiredFields);
            requiredUnit = text(requiredUnit);
            requiredGrain = text(requiredGrain);
            requiredTimeScope = text(requiredTimeScope);
            requiredPopulationScope = text(requiredPopulationScope);
            basedOnEvidenceReferences = texts(basedOnEvidenceReferences);
            route = route == null ? Route.ANALYZE_WITH_LIMITATIONS : route;
            gapId = text(gapId);
            if (gapId.isBlank()) {
                gapId = "semantic-gap:" + fingerprint(List.of(
                    route.name(), String.join("|", rejectionCodes.stream().sorted().toList()),
                    requiredCapabilityId, requiredOperation == null ? "" : requiredOperation.name(),
                    String.join("|", requiredFields.stream().sorted().toList()), requiredUnit,
                    requiredGrain, requiredTimeScope, requiredPopulationScope));
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", SCHEMA_VERSION);
            result.put("gapId", gapId);
            result.put("route", route.name());
            result.put("rejectionCodes", rejectionCodes.stream().sorted().toList());
            result.put("requiredCapabilityId", requiredCapabilityId);
            if (requiredOperation != null) result.put("requiredOperation", requiredOperation.name());
            result.put("requiredFields", requiredFields.stream().sorted().toList());
            put(result, "requiredUnit", requiredUnit);
            put(result, "requiredGrain", requiredGrain);
            put(result, "requiredTimeScope", requiredTimeScope);
            put(result, "requiredPopulationScope", requiredPopulationScope);
            result.put("basedOnEvidenceReferences", basedOnEvidenceReferences.stream().sorted().toList());
            return Map.copyOf(result);
        }
    }

    private static String fingerprint(List<String> values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> texts(Set<String> values) {
        return values == null ? Set.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
