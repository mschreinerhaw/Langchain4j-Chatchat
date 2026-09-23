package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;

import java.util.List;

public record VerificationResult(boolean accepted, List<AnalysisEvidence> acceptedEvidence,
                                 List<String> findings) {
    public VerificationResult {
        acceptedEvidence = acceptedEvidence == null ? List.of() : List.copyOf(acceptedEvidence);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
