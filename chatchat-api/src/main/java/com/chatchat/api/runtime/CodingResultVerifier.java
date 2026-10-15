package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.List;
import java.util.Map;

/** Trusted extension, selected by a persisted Runtime plan; never supplied as executable model code. */
public interface CodingResultVerifier {
    String id();
    String version();
    void validateRules(Map<String, Object> rules);
    CodingEvidenceCollector.Validation verify(KernelDataScope scope,
        CodingInputSnapshotService.Snapshot input, Map<String, Object> rules, List<Map<String, Object>> rows);
}
