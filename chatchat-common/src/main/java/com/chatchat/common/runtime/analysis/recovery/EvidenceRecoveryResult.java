package com.chatchat.common.runtime.analysis.recovery;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;

import java.util.List;
import java.util.Map;

public record EvidenceRecoveryResult(
    RecoveryStatus status,
    EvidenceBundle evidence,
    List<EvidenceGap> remainingGaps,
    int recoveryRound,
    RecoveryStrategy strategy,
    RecoveryLevel level,
    Map<String, Object> metadata
) {
    public EvidenceRecoveryResult {
        if (status == null) throw new IllegalArgumentException("recovery status is required");
        evidence = evidence == null ? EvidenceBundle.empty("Evidence recovery returned no bundle") : evidence;
        remainingGaps = remainingGaps == null ? List.of() : List.copyOf(remainingGaps);
        recoveryRound = Math.max(1, recoveryRound);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
