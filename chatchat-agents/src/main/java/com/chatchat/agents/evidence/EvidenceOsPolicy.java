package com.chatchat.agents.evidence;

public record EvidenceOsPolicy(
    String contractVersion,
    boolean strictMode,
    double minPathConfidence,
    boolean requireExecutablePath,
    boolean requireExecutionVerifiedSql
) {

    public static final String CONTRACT_VERSION = "evidence_os_policy_v2";

    public EvidenceOsPolicy {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        minPathConfidence = minPathConfidence <= 0 ? 0.65 : minPathConfidence;
    }

    public static EvidenceOsPolicy productionDefault() {
        return new EvidenceOsPolicy(CONTRACT_VERSION, true, 0.65, true, true);
    }
}
