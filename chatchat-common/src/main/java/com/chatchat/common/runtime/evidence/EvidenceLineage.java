package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provenance edges connecting derived evidence to its source evidence and execution node. */
public record EvidenceLineage(
    String schemaVersion,
    String evidenceId,
    KernelDataScope scope,
    List<String> parentEvidenceIds,
    String producingTaskId,
    String producingNodeId,
    String toolCallId,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "runtime_evidence_lineage.v1";

    public EvidenceLineage {
        schemaVersion = SCHEMA_VERSION;
        evidenceId = required(evidenceId, "evidenceId");
        requirePartition(scope);
        parentEvidenceIds = parentEvidenceIds == null ? List.of() : parentEvidenceIds.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        producingTaskId = clean(producingTaskId);
        producingNodeId = required(producingNodeId, "producingNodeId");
        toolCallId = clean(toolCallId);
        metadata = metadata == null || metadata.isEmpty()
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requirePartition(KernelDataScope scope) {
        if (scope == null || scope.tenantId() == null
            || (scope.runId() == null && scope.requestId() == null)) {
            throw new IllegalArgumentException("Evidence scope with tenant partition is required");
        }
    }
}
