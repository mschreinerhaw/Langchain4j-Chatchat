package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FailureIsolatedBatchExecutionLayerTest {

    private final FailureIsolatedBatchExecutionLayer layer = new FailureIsolatedBatchExecutionLayer();

    @Test
    void isolatesInvocationExceptionAndRunsEveryLaterChild() {
        List<String> invoked = new ArrayList<>();
        List<ToolCallRequest> calls = List.of(call("first"), call("second"), call("third"));

        List<FailureIsolatedBatchExecutionLayer.Attempt> attempts = layer.execute(calls, (call, index) -> {
            invoked.add(call.callId());
            if ("second".equals(call.callId())) {
                throw new IllegalStateException("child endpoint unavailable");
            }
            return FailureIsolatedBatchExecutionLayer.Invocation.completed(execution(call.callId()));
        });

        assertThat(invoked).containsExactly("first", "second", "third");
        assertThat(attempts).hasSize(3);
        assertThat(attempts).extracting(FailureIsolatedBatchExecutionLayer.Attempt::completed)
            .containsExactly(true, false, true);
        assertThat(attempts.get(1).status()).isEqualTo("FAILED");
        assertThat(attempts.get(1).errorCode()).isEqualTo("BATCH_CHILD_RUNTIME_ERROR");
        assertThat(attempts.get(1).message()).isEqualTo("child endpoint unavailable");
    }

    @Test
    void terminalRuntimeConditionKeepsOneResultSlotPerChild() {
        List<ToolCallRequest> calls = List.of(call("first"), call("second"), call("third"));

        List<FailureIsolatedBatchExecutionLayer.Attempt> attempts = layer.execute(calls, (call, index) ->
            FailureIsolatedBatchExecutionLayer.Invocation.terminal(
                "TIME_BUDGET_EXHAUSTED", "TIME_BUDGET_EXHAUSTED", "deadline exhausted",
                "BATCH_DEADLINE_EXHAUSTED", "not executed after deadline"));

        assertThat(attempts).hasSize(3);
        assertThat(attempts).extracting(FailureIsolatedBatchExecutionLayer.Attempt::status)
            .containsExactly("TIME_BUDGET_EXHAUSTED", "NOT_EXECUTED", "NOT_EXECUTED");
        assertThat(attempts.subList(1, 3)).allSatisfy(attempt -> {
            assertThat(attempt.errorCode()).isEqualTo("BATCH_DEADLINE_EXHAUSTED");
            assertThat(attempt.message()).isEqualTo("not executed after deadline");
        });
    }

    private ToolCallRequest call(String callId) {
        return new ToolCallRequest(callId, "generic_executor", Map.of("contractId", callId));
    }

    private ToolRuntimeExecution execution(String outcome) {
        return new ToolRuntimeExecution(null, null, null, outcome, Map.of());
    }
}
