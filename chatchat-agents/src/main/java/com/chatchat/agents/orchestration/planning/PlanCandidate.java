package com.chatchat.agents.orchestration.planning;

import java.util.Map;

/** Auditable candidate and its deterministic Runtime score. */
public record PlanCandidate(
    int attempt,
    String label,
    String raw,
    AgentDecision decision,
    String failurePattern,
    String fingerprint,
    int deterministicScore,
    Map<String, Object> deterministicScoreDetails
) {
}
