package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.List;

/** Partition-safe query over registered Runtime evidence. */
public record EvidenceQuery(
    KernelDataScope scope,
    List<String> evidenceTypes,
    List<String> sourceNodes,
    long occurredAfterEpochMs,
    long occurredBeforeEpochMs,
    int limit
) {
    public EvidenceQuery {
        if (scope == null || scope.tenantId() == null
            || (scope.runId() == null && scope.requestId() == null)) {
            throw new IllegalArgumentException("Evidence scope with tenant partition is required");
        }
        evidenceTypes = clean(evidenceTypes);
        sourceNodes = clean(sourceNodes);
        occurredAfterEpochMs = Math.max(0L, occurredAfterEpochMs);
        occurredBeforeEpochMs = occurredBeforeEpochMs <= 0 ? Long.MAX_VALUE : occurredBeforeEpochMs;
        if (occurredBeforeEpochMs < occurredAfterEpochMs) {
            throw new IllegalArgumentException("Evidence query time range is invalid");
        }
        limit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 10_000));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }
}
