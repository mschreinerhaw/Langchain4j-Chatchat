package com.chatchat.agents.evidence;

import java.util.List;

public record EvidenceGovernance(
    String tenantId,
    String userId,
    List<String> roles,
    String policyStatus
) {

    public EvidenceGovernance {
        roles = roles == null ? List.of() : List.copyOf(roles);
        policyStatus = policyStatus == null || policyStatus.isBlank() ? "ALLOWED" : policyStatus;
    }
}
