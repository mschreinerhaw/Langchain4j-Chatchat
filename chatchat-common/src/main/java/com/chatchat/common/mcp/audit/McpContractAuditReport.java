package com.chatchat.common.mcp.audit;

import java.util.List;
import java.util.Map;

public record McpContractAuditReport(
    String schemaVersion,
    boolean compliant,
    List<McpContractEvidence> evidence,
    List<McpContractFinding> findings,
    Map<String, Long> findingCounts,
    long auditedAt
) {
    public static final String SCHEMA_VERSION = "mcp_contract_audit.v1";

    public McpContractAuditReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        findings = findings == null ? List.of() : List.copyOf(findings);
        findingCounts = findingCounts == null ? Map.of() : Map.copyOf(findingCounts);
        auditedAt = auditedAt <= 0 ? System.currentTimeMillis() : auditedAt;
    }
}
