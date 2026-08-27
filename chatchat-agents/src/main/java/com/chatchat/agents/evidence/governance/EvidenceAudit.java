package com.chatchat.agents.evidence.governance;

import com.chatchat.agents.evidence.normalization.EvidenceType;

public record EvidenceAudit(
    String query,
    String toolName,
    EvidenceType evidenceType,
    String tenantId,
    String userId,
    String policyStatus,
    boolean citationUsed,
    String refId
) {
}
