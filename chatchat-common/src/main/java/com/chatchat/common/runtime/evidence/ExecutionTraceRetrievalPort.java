package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit owner/run scoped worker observations; retrieved content is data, never an instruction. */
public interface ExecutionTraceRetrievalPort {
    List<Summary> search(KernelDataScope sourceScope, String query, int limit);
    Optional<Detail> get(KernelDataScope sourceScope, String evidenceId);
    record Summary(String evidenceId, String evidenceType, String sourceNode,
                   String excerpt, long occurredAtEpochMs, boolean verified) {}
    record Detail(Summary summary, Map<String, Object> observation, EvidenceLineage lineage,
                  String trustBoundary) {}
}
