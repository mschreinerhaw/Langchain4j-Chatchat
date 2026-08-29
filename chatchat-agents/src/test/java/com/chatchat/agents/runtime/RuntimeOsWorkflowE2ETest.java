package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.batch.FailureIsolatedBatchExecutionLayer;
import com.chatchat.agents.runtime.batch.ToolCallRequest;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime OS acceptance path from admitted calls to terminal evidence state. */
class RuntimeOsWorkflowE2ETest {

    @Test
    void partialMcpFailurePreservesEvidenceAndSchedulesOnlyMissingEvidenceRecovery() {
        FailureIsolatedBatchExecutionLayer layer = new FailureIsolatedBatchExecutionLayer();
        List<String> remoteInvocations = new ArrayList<>();
        List<ToolCallRequest> calls = List.of(call("assets"), call("positions"), call("profit"));

        List<FailureIsolatedBatchExecutionLayer.Attempt> attempts = layer.execute(calls, (call, index) -> {
            remoteInvocations.add(call.callId());
            if ("positions".equals(call.callId())) {
                return FailureIsolatedBatchExecutionLayer.Invocation.failed(
                    "FAILED", "UPSTREAM_UNAVAILABLE", "position endpoint unavailable");
            }
            return FailureIsolatedBatchExecutionLayer.Invocation.completed(
                new ToolRuntimeExecution(null, null, null,
                    "evidence-returned",
                    Map.of("callId", call.callId(), "rows", List.of(Map.of("value", index + 1)))));
        });

        long completed = attempts.stream().filter(FailureIsolatedBatchExecutionLayer.Attempt::completed).count();
        long failed = attempts.size() - completed;
        DiagnosticRunStateMachine.Snapshot state = DiagnosticRunStateMachine.resolveEvidenceOnly(
            (int) completed, (int) failed, 0, true, 1);

        assertThat(remoteInvocations).containsExactly("assets", "positions", "profit");
        assertThat(attempts).hasSize(calls.size());
        assertThat(attempts.get(0).execution().outcome()).isEqualTo("evidence-returned");
        assertThat(attempts.get(2).execution().outcome()).isEqualTo("evidence-returned");
        assertThat(state.state()).isEqualTo(DiagnosticRunStateMachine.State.REPAIRING);
        assertThat(state.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(state.recoveryAction())
            .isEqualTo(DiagnosticRunStateMachine.RecoveryAction.RETRY_MISSING_EVIDENCE);
    }

    @Test
    void deadlineStopsRemoteCallsButAssignsEveryChildAnExplicitTerminalState() {
        FailureIsolatedBatchExecutionLayer layer = new FailureIsolatedBatchExecutionLayer();
        List<String> remoteInvocations = new ArrayList<>();
        List<FailureIsolatedBatchExecutionLayer.Attempt> attempts = layer.execute(
            List.of(call("first"), call("deadline"), call("never-invoked")), (call, index) -> {
                remoteInvocations.add(call.callId());
                if ("deadline".equals(call.callId())) {
                    return FailureIsolatedBatchExecutionLayer.Invocation.terminal(
                        "TIME_BUDGET_EXHAUSTED", "TIME_BUDGET_EXHAUSTED", "deadline exhausted",
                        "BATCH_DEADLINE_EXHAUSTED", "not executed after deadline");
                }
                return FailureIsolatedBatchExecutionLayer.Invocation.completed(
                    new ToolRuntimeExecution(null, null, null, "evidence", Map.of()));
            });

        DiagnosticRunStateMachine.Snapshot state = DiagnosticRunStateMachine.resolve(
            "TIME_BUDGET_EXHAUSTED", false, 1, 0, 2, true, 3);

        assertThat(remoteInvocations).containsExactly("first", "deadline");
        assertThat(attempts).extracting(FailureIsolatedBatchExecutionLayer.Attempt::status)
            .containsExactly(null, "TIME_BUDGET_EXHAUSTED", "NOT_EXECUTED");
        assertThat(state.state()).isEqualTo(DiagnosticRunStateMachine.State.FAILED);
        assertThat(state.outcome()).isEqualTo(DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS);
        assertThat(state.recoveryAction()).isNull();
    }

    private ToolCallRequest call(String callId) {
        return new ToolCallRequest(callId, "governed_executor", Map.of("contractId", callId));
    }
}
