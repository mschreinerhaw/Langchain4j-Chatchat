package com.chatchat.api.runtime.coding.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Execution-side provenance check. Source values are hashed locally, never projected into model diagnostics. */
final class ComputedResultBoundary {
    // Short quotations and identifiers can be legitimate evidence in text analysis.
    private static final int MIN_COPY_CHARS = 2048;
    private final ObjectMapper json;
    private final Set<String> sourceFingerprints = new HashSet<>();
    private final Set<String> sourceTexts = new HashSet<>();
    private final Set<String> sourceRecords = new HashSet<>();

    ComputedResultBoundary(ObjectMapper mapper) {
        json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    void observeInput(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        remember(text);
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            try { visit(json.readTree(line), true, 0); }
            catch (java.io.IOException nonJsonSource) { /* CSV/text is still checked as a complete document. */ }
        }
    }

    boolean copiedSource(List<Map<String, Object>> rows) {
        if (rows.stream().filter(row -> sourceRecords.contains(canonicalHash(json.valueToTree(row)))).limit(2).count() >= 2)
            return true;
        return rows.stream().anyMatch(row -> visit(json.valueToTree(row), false, 0));
    }

    private boolean visit(JsonNode node, boolean source, int depth) {
        if (node == null || depth > 64) return !source && depth > 64;
        if (node.isTextual()) {
            String text = node.asText();
            if (source) remember(text);
            else {
                if (matches(text)) return true;
                // A JSON string wrapper must not evade the record/collection comparison.
                if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
                    try { if (visit(json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(text), false, depth + 1)) return true; }
                    catch (java.io.IOException nonJsonFinding) { /* Original prose is allowed. */ }
                }
            }
        } else if (node.isContainerNode()) {
            String canonical;
            try { canonical = json.writeValueAsString(json.convertValue(node, Object.class)); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("Result provenance cannot be compared", invalid); }
            if (source) remember(canonical, false);
            else if (matches(canonical)) return true;
            if (source && node.isObject()) sourceRecords.add(hash(canonical));
            if (!source && node.isArray()) {
                int copied = 0;
                for (JsonNode child : node)
                    if (child.isObject() && sourceRecords.contains(canonicalHash(child)) && ++copied >= 2) return true;
            }
            for (JsonNode child : node) if (visit(child, source, depth + 1)) return true;
        }
        return false;
    }

    private String canonicalHash(JsonNode value) {
        try { return hash(json.writeValueAsString(json.convertValue(value, Object.class))); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Record provenance cannot be compared", invalid); }
    }

    private void remember(String value) {
        remember(value, true);
    }

    private void remember(String value, boolean text) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        if (normalized.length() >= MIN_COPY_CHARS) {
            sourceFingerprints.add(hash(normalized));
            // Keep only execution-local document/scalar text, not every nested container copy.
            if (text) sourceTexts.add(normalized);
        }
    }

    private boolean matches(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() >= MIN_COPY_CHARS && (sourceFingerprints.contains(hash(normalized))
            || sourceTexts.stream().anyMatch(normalized::contains));
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
