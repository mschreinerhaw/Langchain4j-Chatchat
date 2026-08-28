package com.chatchat.chat.skills.release;

import java.util.List;

public record AgentReleaseQualityReport(
    String contractVersion,
    boolean passed,
    int total,
    int passedCount,
    List<Check> checks
) {
    public static final String CONTRACT_VERSION = "agent_release_quality_v1";

    public AgentReleaseQualityReport {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public record Check(String id, boolean passed, String message) {
    }
}
