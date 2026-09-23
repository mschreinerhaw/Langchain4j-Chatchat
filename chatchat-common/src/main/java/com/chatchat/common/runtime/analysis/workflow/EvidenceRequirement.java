package com.chatchat.common.runtime.analysis.workflow;

public record EvidenceRequirement(String type, boolean required, int minimumCount, String verificationRule) {
    public EvidenceRequirement {
        minimumCount = Math.max(0, minimumCount);
        verificationRule = verificationRule == null ? "" : verificationRule;
    }
}
