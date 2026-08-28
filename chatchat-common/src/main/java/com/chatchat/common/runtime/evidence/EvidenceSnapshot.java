package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned immutable view of the evidence set used at a review or synthesis boundary. */
public record EvidenceSnapshot(
    String schemaVersion,
    String snapshotId,
    KernelDataScope scope,
    long revision,
    List<String> evidenceIds,
    long createdAtEpochMs,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "runtime_evidence_snapshot.v1";

    public EvidenceSnapshot {
        schemaVersion = SCHEMA_VERSION;
        snapshotId = required(snapshotId, "snapshotId");
        if (scope == null || scope.tenantId() == null
            || (scope.runId() == null && scope.requestId() == null)) {
            throw new IllegalArgumentException("Evidence scope with tenant partition is required");
        }
        revision = Math.max(1L, revision);
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        createdAtEpochMs = createdAtEpochMs <= 0 ? System.currentTimeMillis() : createdAtEpochMs;
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
