package com.chatchat.agents.evidence;

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
