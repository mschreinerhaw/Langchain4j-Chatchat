package com.chatchat.agents.runtime.run;


import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOutcomeProjectionTest {

    private final AgentOutcomeProjection projection = new AgentOutcomeProjection();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void fatalBlockClosesRunEvenWhenRequiredNodesNeverStarted(boolean mandatoryBlocked) {
        var outcome = projection.project(Map.of(
            "fatalExecutionBlocked", true, "mandatoryWorkflowBlocked", mandatoryBlocked,
            "mandatoryWorkflowPending", true, "mandatoryWorkflowTerminal", false),
            "Required nodes could not run after dependency rejection.");
        assertThat(outcome.runStatus()).isEqualTo("FAILED");
        assertThat(outcome.publicStatus()).isEqualTo("FAILED");
        assertThat(outcome.answerStatus()).isEqualTo("FAILED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"cancelled,CANCELLED", "time_budget_exhausted,TIME_BUDGET_EXHAUSTED"})
    void explicitInterruptionWinsOverDependencyFailure(String reason, String status) {
        assertThat(projection.project(Map.of("fatalExecutionBlocked", true,
            "stopReason", reason), "").publicStatus()).isEqualTo(status);
    }

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

    @Test
    void withheldRawAnalysisOutputFailsTheRunEvenWithAnExplanatoryMessage() {
        AgentOutcomeProjection.Outcome outcome = projection.project(Map.of(
            "rawAnalysisOutputWithheld", true,
            "executionStatus", "NO_PRESENTABLE_ANALYSIS"
        ), "Analysis synthesis failed; raw evidence was withheld.");

        assertThat(outcome.runStatus()).isEqualTo("FAILED");
        assertThat(outcome.answerStatus()).isEqualTo("FAILED");
        assertThat(outcome.workflowStatus()).isEqualTo("ANALYSIS_SYNTHESIS_FAILED");
        assertThat(outcome.publicStatus()).isEqualTo("FAILED");
    }
}
