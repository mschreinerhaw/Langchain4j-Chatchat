package com.chatchat.common.runtime.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable registration receipt returned by an Evidence Store implementation. */
public record EvidenceRegistration(
    String schemaVersion,
    String evidenceId,
    String partitionKey,
    String storeReference,
    long revision,
    long storedAtEpochMs,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "runtime_evidence_registration.v1";

    public EvidenceRegistration {
        schemaVersion = SCHEMA_VERSION;
        evidenceId = required(evidenceId, "evidenceId");
        partitionKey = required(partitionKey, "partitionKey");
        storeReference = required(storeReference, "storeReference");
        revision = Math.max(1L, revision);
        storedAtEpochMs = storedAtEpochMs <= 0 ? System.currentTimeMillis() : storedAtEpochMs;
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
