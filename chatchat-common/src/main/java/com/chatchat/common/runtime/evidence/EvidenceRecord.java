package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable evidence registered by one Runtime OS node or enterprise capability. */
public record EvidenceRecord(
    String schemaVersion,
    String evidenceId,
    KernelDataScope scope,
    String evidenceType,
    String sourceNode,
    String contentSha256,
    Map<String, Object> payload,
    String payloadReference,
    long occurredAtEpochMs,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "runtime_evidence.v1";

    public EvidenceRecord {
        schemaVersion = SCHEMA_VERSION;
        evidenceId = required(evidenceId, "evidenceId");
        if (scope == null || scope.tenantId() == null
            || (scope.runId() == null && scope.requestId() == null)) {
            throw new IllegalArgumentException("Evidence scope with tenant partition is required");
        }
        evidenceType = required(evidenceType, "evidenceType");
        sourceNode = required(sourceNode, "sourceNode");
        contentSha256 = required(contentSha256, "contentSha256");
        payload = immutable(payload);
        payloadReference = clean(payloadReference);
        if (payload.isEmpty() && payloadReference == null) {
            throw new IllegalArgumentException("Evidence payload or payloadReference is required");
        }
        occurredAtEpochMs = occurredAtEpochMs <= 0
            ? System.currentTimeMillis() : occurredAtEpochMs;
        metadata = immutable(metadata);
    }

    public String partitionKey() {
        return scope.partitionKey();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
