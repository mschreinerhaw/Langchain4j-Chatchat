package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticRunStateMachineTest {

    @Test
    void separatesLifecycleOutcomeFailureAndRecovery() {
        DiagnosticRunStateMachine.Snapshot snapshot = DiagnosticRunStateMachine.resolve(
            DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED.wireValue(),
            false,
            4,
            1,
            0,
            true,
            1
        );

        assertThat(snapshot.state()).isEqualTo(DiagnosticRunStateMachine.State.REPAIRING);
        assertThat(snapshot.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(snapshot.failureCode())
            .isEqualTo(DiagnosticRunStateMachine.FailureCode.STEP_OUTPUT_CONTRACT_FAILED);
        assertThat(snapshot.recoveryAction())
            .isEqualTo(DiagnosticRunStateMachine.RecoveryAction.REWRITE_PLAN);
    }

    @Test
    void enforcesLifecycleTransitions() {
        assertThat(DiagnosticRunStateMachine.canTransition(
            DiagnosticRunStateMachine.State.INIT,
            DiagnosticRunStateMachine.State.PLANNING
        )).isTrue();
        assertThat(DiagnosticRunStateMachine.canTransition(
            DiagnosticRunStateMachine.State.EXECUTING,
            DiagnosticRunStateMachine.State.REPAIRING
        )).isTrue();
        assertThat(DiagnosticRunStateMachine.canTransition(
            DiagnosticRunStateMachine.State.COMPLETED,
            DiagnosticRunStateMachine.State.EXECUTING
        )).isFalse();
    }

    @Test
    void budgetExhaustionIsTerminalAndDoesNotScheduleAnotherRepair() {
        DiagnosticRunStateMachine.Snapshot snapshot = DiagnosticRunStateMachine.resolve(
            DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue(),
            false,
            3,
            0,
            2,
            true,
            2
        );

        assertThat(snapshot.state()).isEqualTo(DiagnosticRunStateMachine.State.FAILED);
        assertThat(snapshot.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(snapshot.failureCode())
            .isEqualTo(DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED);
        assertThat(snapshot.recoveryAction()).isNull();
    }

    @Test
    void preservesExistingWireValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThat(objectMapper.writeValueAsString(
            DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS
        )).isEqualTo("\"PARTIAL_SUCCESS\"");
        assertThat(objectMapper.writeValueAsString(
            DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED
        )).isEqualTo("\"TIME_BUDGET_EXHAUSTED\"");
        assertThat(objectMapper.writeValueAsString(
            DiagnosticRunStateMachine.RecoveryAction.REWRITE_PLAN
        )).isEqualTo("\"rewrite_plan\"");
    }
}
