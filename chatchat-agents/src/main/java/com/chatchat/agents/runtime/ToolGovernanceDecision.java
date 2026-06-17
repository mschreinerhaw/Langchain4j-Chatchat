package com.chatchat.agents.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolGovernanceDecision(
    String contractVersion,
    String tenantId,
    String userId,
    List<String> roles,
    String riskLevel,
    String toolRiskLevel,
    boolean confirmRequired,
    boolean confirmed,
    String auditId,
    String policyDecision,
    String policyAction,
    String policyReason,
    String runtimeLevel,
    String operationType,
    String dataScope,
    List<String> matchedRules
) {

    public static final String CONTRACT_VERSION = "tool_governance_v1";

    public ToolGovernanceDecision {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        roles = roles == null ? List.of() : List.copyOf(roles);
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contractVersion", contractVersion);
        values.put("tenantId", tenantId);
        values.put("userId", userId);
        values.put("roles", roles);
        values.put("riskLevel", riskLevel);
        values.put("toolRiskLevel", toolRiskLevel);
        values.put("confirmRequired", confirmRequired);
        values.put("confirmed", confirmed);
        values.put("auditId", auditId);
        values.put("policyDecision", policyDecision);
        values.put("policyAction", policyAction);
        values.put("policyReason", policyReason);
        values.put("runtimeLevel", runtimeLevel);
        values.put("operationType", operationType);
        values.put("dataScope", dataScope);
        values.put("matchedRules", matchedRules);
        return values;
    }
}
