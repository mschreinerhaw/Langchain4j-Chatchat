package com.chatchat.api.runtime;

import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.*;

/** Stable across service restarts; process-local registry counters remain useful only for invocation admission. */
final class AnalysisToolContractIdentity {
    static final String SCHEMA_VERSION = "tool_contract_identity.v2";
    private AnalysisToolContractIdentity() {}
    static String fingerprint(ToolMetadata metadata, ObjectMapper mapper) {
        try {
            Map<String, Object> canonical = mapper.convertValue(metadata,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (canonical.get("parameters") instanceof List<?> parameters) {
                var sorted = new ArrayList<Map<String, Object>>();
                Set<String> names = new HashSet<>();
                for (Object raw : parameters) {
                    if (!(raw instanceof Map<?, ?> parameter) || !(parameter.get("name") instanceof String name)
                        || name.isBlank() || !names.add(name)) throw new IllegalArgumentException("Ambiguous parameter identity");
                    Map<String, Object> item = new LinkedHashMap<>();
                    parameter.forEach((key, value) -> item.put(String.valueOf(key), value));
                    sorted.add(item);
                }
                sorted.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
                canonical.put("parameters", sorted);
            }
            return hash(Map.of("schemaVersion", SCHEMA_VERSION, "contract", canonical), mapper);
        } catch (Exception invalid) { throw new IllegalStateException("Published tool contract cannot be fingerprinted", invalid); }
    }
    static boolean matches(String fingerprint, Object version, ToolMetadata metadata, ObjectMapper mapper) {
        if (SCHEMA_VERSION.equals(version)) return fingerprint.equals(fingerprint(metadata, mapper));
        // Old records did not retain the parameter ordering: do not guess or weaken their identity check.
        return (version == null || "".equals(version)) && fingerprint.equals(legacyFingerprint(metadata, mapper));
    }
    static String legacyFingerprint(ToolMetadata metadata, ObjectMapper mapper) {
        return hash(metadata, mapper);
    }
    private static String hash(Object metadata, ObjectMapper mapper) {
        try {
            byte[] bytes = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception invalid) { throw new IllegalStateException("Published tool contract cannot be fingerprinted", invalid); }
    }
}
