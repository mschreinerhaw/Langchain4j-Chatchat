package com.chatchat.agents.runtime.run;


import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOutcomeProjectionTest {

    private final AgentOutcomeProjection projection = new AgentOutcomeProjection();

    @Test
    void mandatoryEvidenceFailureIsACompletedRunWithPartialAnswer() {
        AgentOutcomeProjection.Outcome outcome = projection.project(Map.of(
            "mandatoryWorkflowBlocked", true,
            "failedMandatoryTools", java.util.List.of("metadata_search")
        ), "Partial evidence is available.");

        assertThat(outcome.runStatus()).isEqualTo("COMPLETED");
        assertThat(outcome.answerStatus()).isEqualTo("PARTIAL");
        assertThat(outcome.workflowStatus()).isEqualTo("FAILED_REQUIRED_EVIDENCE");
        assertThat(outcome.publicStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(outcome.contractVersion()).isEqualTo("ui_response_v2");
    }

    @Test
    void successfulRunUsesOneConsistentProjection() {
        assertThat(projection.enrich(Map.of(), "done"))
            .containsEntry("runStatus", "COMPLETED")
            .containsEntry("answerStatus", "SUCCESS")
            .containsEntry("workflowStatus", "COMPLETED")
            .containsEntry("publicStatus", "SUCCESS")
            .containsEntry("contractVersion", "ui_response_v2");
    }

    @Test
    void pendingMandatoryWorkflowRemainsRecoverable() {
        AgentOutcomeProjection.Outcome outcome = projection.project(Map.of(
            "mandatoryWorkflowBlocked", true,
            "mandatoryWorkflowPending", true,
            "mandatoryWorkflowTerminal", false,
            "unattemptedMandatoryTools", java.util.List.of("metadata_search")
        ), "Partial evidence is available.");

        assertThat(outcome.runStatus()).isEqualTo("RUNNING");
        assertThat(outcome.answerStatus()).isEqualTo("PARTIAL");
        assertThat(outcome.workflowStatus()).isEqualTo("PENDING_REQUIRED_EVIDENCE");
        assertThat(outcome.publicStatus()).isEqualTo("RUNNING");
    }
}
