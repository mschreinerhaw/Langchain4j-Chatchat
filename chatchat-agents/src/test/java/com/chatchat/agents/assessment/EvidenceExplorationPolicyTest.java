package com.chatchat.agents.assessment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExplorationPolicyTest {

    private final EvidenceExplorationPolicy policy = new EvidenceExplorationPolicy();

    @Test
    void expandsADeclaredBusinessGapButStopsAtAuthoritativeEmptyDiscovery() {
        assertThat(policy.available(Map.of(
            "toolEvidence", List.of(Map.of("shouldExpandQuery", true))),
            true, true, true, false)).isTrue();

        Map<String, Object> authoritativeEmpty = Map.of("toolEvidence", List.of(Map.of(
            "output", Map.of("preview", Map.of(
                "success", true, "returnedCount", 0, "records", List.of())))));
        assertThat(policy.available(authoritativeEmpty, false, true, true, false)).isFalse();
    }

    @Test
    void requiresBothToolsAndBudget() {
        Map<String, Object> gap = Map.of("nextActions", List.of(Map.of("action", "discover")));
        assertThat(policy.available(gap, true, false, true, false)).isFalse();
        assertThat(policy.available(gap, true, true, false, false)).isFalse();
    }
}
