package com.chatchat.agents.orchestration.planning;

import java.util.List;

/** Bounded candidate history supplied to repair and attribution policy. */
public record PlanRewriteContext(
    int rewriteCount,
    List<PlanCandidate> candidates,
    String lastFailureReason,
    String failurePattern
) {
}
