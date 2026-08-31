package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministically projects complete Runtime evidence into a bounded scheduling view.
 * Complete evidence remains Runtime-owned and is never replaced by this projection.
 */
final class EvidenceCompressionGate {

    static final String CONTRACT_VERSION = "evidence_compression_gate_v1";
    static final int OBSERVATION_BUDGET_CHARS = 12_000;
    static final int HISTORY_BUDGET_CHARS = 12_000;
    private static final int MAX_OBSERVATIONS = 24;
    private static final int MAX_MAP_FIELDS = 32;
    private static final int MAX_COLLECTION_SAMPLES = 6;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_SCALAR_CHARS = 768;
    private static final int MAX_OBSERVATION_CHARS = 1_600;

    private final ObjectMapper objectMapper;

    EvidenceCompressionGate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    CompressionResult compress(List<String> observations,
                               List<Map<String, Object>> evidenceHistory) {
        List<String> sourceObservations = observations == null ? List.of() : observations.stream()
            .filter(value -> value != null && !value.isBlank())
            .toList();
        List<Map<String, Object>> sourceHistory = evidenceHistory == null ? List.of() : evidenceHistory;
        int observationCharsBefore = serialize(sourceObservations).length();
        int historyCharsBefore = serialize(sourceHistory).length();

        List<String> selected = selectObservations(sourceObservations);
        int perObservation = Math.min(MAX_OBSERVATION_CHARS, Math.max(320,
            (OBSERVATION_BUDGET_CHARS - 1_000) / Math.max(1, selected.size())));
        List<Map<String, Object>> observationProjection = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            String value = selected.get(index);
            Object parsed = parseJson(value);
            String content = parsed == null
                ? boundedText(value, perObservation)
                : boundedText(serialize(compactValue(parsed, 0)), perObservation);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("evidenceRef", "observation:" + digest(value));
            envelope.put("sourceIndex", sourceObservations.indexOf(value));
            envelope.put("originalChars", value.length());
            envelope.put("content", content);
            observationProjection.add(envelope);
        }
        String compressedObservations = boundedDocument(
            observationProjection,
            OBSERVATION_BUDGET_CHARS,
            "observations",
            observationCharsBefore
        );
        Object compactHistory = compactValue(sourceHistory, 0);
        String compressedHistory = boundedDocument(
            compactHistory,
            HISTORY_BUDGET_CHARS,
            "evidenceHistory",
            historyCharsBefore
        );
        int charsBefore = observationCharsBefore + historyCharsBefore;
        int charsAfter = compressedObservations.length() + compressedHistory.length();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contractVersion", CONTRACT_VERSION);
        metadata.put("applied", charsAfter < charsBefore || selected.size() < sourceObservations.size());
        metadata.put("originalChars", charsBefore);
        metadata.put("compressedChars", charsAfter);
        metadata.put("savedChars", Math.max(0, charsBefore - charsAfter));
        metadata.put("compressionRatio", charsBefore == 0 ? 1.0 : charsAfter / (double) charsBefore);
        metadata.put("observationCount", sourceObservations.size());
        metadata.put("selectedObservationCount", selected.size());
        metadata.put("omittedObservationCount", Math.max(0, sourceObservations.size() - selected.size()));
        metadata.put("observationBudgetChars", OBSERVATION_BUDGET_CHARS);
        metadata.put("historyBudgetChars", HISTORY_BUDGET_CHARS);
        metadata.put("fullEvidenceRetainedByRuntime", true);
        return new CompressionResult(compressedObservations, compressedHistory, Map.copyOf(metadata));
    }

    private List<String> selectObservations(List<String> observations) {
        Map<String, String> uniqueByDigest = new LinkedHashMap<>();
        for (String observation : observations) {
            uniqueByDigest.putIfAbsent(digest(observation), observation);
        }
        List<String> unique = List.copyOf(uniqueByDigest.values());
        if (unique.size() <= MAX_OBSERVATIONS) {
            return unique;
        }
        List<String> selected = new ArrayList<>(MAX_OBSERVATIONS);
        selected.addAll(unique.subList(0, 4));
        selected.addAll(unique.subList(unique.size() - (MAX_OBSERVATIONS - 4), unique.size()));
        return List.copyOf(selected);
    }

    private Object compactValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return boundedText(text.toString(), MAX_SCALAR_CHARS);
        }
        if (depth >= MAX_DEPTH) {
            return shape(value);
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparingInt(entry -> keyPriority(String.valueOf(entry.getKey()))));
            Map<String, Object> compact = new LinkedHashMap<>();
            int retained = Math.min(entries.size(), MAX_MAP_FIELDS);
            for (int index = 0; index < retained; index++) {
                Map.Entry<?, ?> entry = entries.get(index);
                compact.put(String.valueOf(entry.getKey()), compactValue(entry.getValue(), depth + 1));
            }
            if (entries.size() > retained) {
                compact.put("_omittedFieldCount", entries.size() - retained);
                compact.put("_allFieldNames", entries.stream().map(entry -> String.valueOf(entry.getKey())).sorted().toList());
            }
            return compact;
        }
        if (value instanceof Collection<?> collection) {
            List<?> values = new ArrayList<>(collection);
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("count", values.size());
            if (!values.isEmpty()) {
                List<Object> samples = new ArrayList<>();
                int head = Math.min(4, values.size());
                for (int index = 0; index < head; index++) {
                    samples.add(compactValue(values.get(index), depth + 1));
                }
                int tailStart = Math.max(head, values.size() - Math.max(0, MAX_COLLECTION_SAMPLES - head));
                for (int index = tailStart; index < values.size(); index++) {
                    samples.add(compactValue(values.get(index), depth + 1));
                }
                compact.put("samples", samples);
                compact.put("omittedCount", Math.max(0, values.size() - samples.size()));
            }
            return compact;
        }
        return boundedText(String.valueOf(value), MAX_SCALAR_CHARS);
    }

    private Object shape(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Map.of(
                "shape", "map",
                "fieldCount", map.size(),
                "fields", map.keySet().stream().map(String::valueOf).sorted().toList()
            );
        }
        if (value instanceof Collection<?> collection) {
            return Map.of("shape", "collection", "count", collection.size());
        }
        return Map.of("shape", value.getClass().getSimpleName());
    }

    private int keyPriority(String key) {
        String normalized = key == null ? "" : key.replace("_", "").toLowerCase(Locale.ROOT);
        if (List.of("evidenceid", "evidenceref", "runid", "traceid", "stepid", "toolname",
            "status", "success", "error", "errorcode", "reason").contains(normalized)) {
            return 0;
        }
        if (normalized.contains("missing") || normalized.contains("conflict")
            || normalized.contains("nextaction") || normalized.contains("hypothes")
            || normalized.contains("analysiscoverage") || normalized.contains("gaprequest")
            || normalized.contains("gapfingerprint")
            || normalized.contains("evaluation") || normalized.contains("executionlock")
            || normalized.contains("repair") || normalized.contains("contract")) {
            return 1;
        }
        if (normalized.endsWith("count") || normalized.endsWith("size")
            || normalized.contains("summary") || normalized.contains("evidence")) {
            return 2;
        }
        return 3;
    }

    private Object parseJson(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<Object>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    private String boundedDocument(Object projection, int budget, String source, int originalChars) {
        String serialized = serialize(projection);
        if (serialized.length() <= budget) {
            return serialized;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", CONTRACT_VERSION);
        envelope.put("source", source);
        envelope.put("evidenceRef", source + ":" + digest(serialized));
        envelope.put("originalChars", originalChars);
        envelope.put("projectionChars", serialized.length());
        envelope.put("projectionPreview", boundedText(serialized, Math.max(256, budget - 400)));
        envelope.put("fullEvidenceRetainedByRuntime", true);
        String bounded = serialize(envelope);
        if (bounded.length() <= budget) {
            return bounded;
        }
        envelope.put("projectionPreview", boundedText(serialized, Math.max(128, budget - 800)));
        return serialize(envelope);
    }

    private String boundedText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        int markerBudget = Math.min(160, Math.max(64, maxChars / 5));
        String marker = "...[omitted chars=" + (value.length() - maxChars) + ", evidenceRef=" + digest(value) + "]...";
        int available = Math.max(2, maxChars - Math.max(markerBudget, marker.length()));
        int head = available * 2 / 3;
        int tail = available - head;
        return value.substring(0, head) + marker + value.substring(value.length() - tail);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    record CompressionResult(
        String observations,
        String evidenceHistory,
        Map<String, Object> metadata
    ) {
    }
}
