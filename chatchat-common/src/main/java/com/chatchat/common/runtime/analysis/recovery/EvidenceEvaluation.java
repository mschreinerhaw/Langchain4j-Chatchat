package com.chatchat.common.runtime.analysis.recovery;

import java.util.List;
import java.util.Map;

public record EvidenceEvaluation(
    Decision decision,
    List<EvidenceGap> gaps,
    double coverage,
    Map<String, Object> diagnostics
) {
    public enum Decision { SUFFICIENT, GAP }

    public EvidenceEvaluation {
        if (decision == null) throw new IllegalArgumentException("evidence decision is required");
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        coverage = Math.max(0D, Math.min(1D, coverage));
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public boolean sufficient() { return decision == Decision.SUFFICIENT; }
}
