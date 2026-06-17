package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentObservationPipelineTest {

    private final DefaultAgentObservationPipeline pipeline = new DefaultAgentObservationPipeline();

    @Test
    void classifiesWebEvidenceObservationWithTrustAndCitations() {
        AgentObservation observation = pipeline.fromText("""
            Tool search_and_extract succeeded.
            Evidence trust policy: version=agent_evidence_trust_policy_v1, usable=1.
            Web citation map. Use these labels in the final answer when relying on web search evidence:
            [网页1] Source - https://example.com
            """);

        assertThat(observation.type()).isEqualTo("web_evidence");
        assertThat(observation.source()).isEqualTo("search_and_extract");
        assertThat(observation.metadata())
            .containsEntry("legacyTextObservation", true)
            .containsEntry("containsTrustPolicy", true)
            .containsEntry("containsCitations", true);
    }

    @Test
    void classifiesPlannerAndToolFailureObservations() {
        AgentObservation planner = pipeline.fromText("Planner requested unavailable tool: sql_write");
        AgentObservation failure = pipeline.fromText("Tool web_search failed. Error: timeout");

        assertThat(planner.type()).isEqualTo("planner");
        assertThat(planner.source()).isEqualTo("planner");
        assertThat(failure.type()).isEqualTo("tool_failure");
        assertThat(failure.source()).isEqualTo("web_search");
    }

    @Test
    void classifiesUnifiedEvidenceObservation() {
        AgentObservation observation = pipeline.fromText("""
            Tool web_search succeeded.
            Unified evidence context (contractVersion=evidence_v1):
            [Evidence 1]
            type: WEB
            citation: web://example.com/a#result=1
            content:
            evidence
            """);

        assertThat(observation.type()).isEqualTo("evidence");
        assertThat(observation.metadata())
            .containsEntry("containsUnifiedEvidence", true)
            .containsEntry("evidenceContractVersion", "evidence_v1");
    }
}
