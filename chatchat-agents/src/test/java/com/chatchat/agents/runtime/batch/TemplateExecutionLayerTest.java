package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateExecutionLayerTest {

    private final TemplateExecutionLayer layer = new TemplateExecutionLayer();

    @Test
    void preservesTemplateGovernanceWhileKernelIsolatesChildFailures() {
        List<String> invoked = new ArrayList<>();
        List<ToolCallRequest> calls = List.of(call("first"), call("second"), call("third"));

        List<TemplateExecutionLayer.Attempt> attempts = layer.execute(calls, (call, index) -> {
            invoked.add(call.callId());
            if ("second".equals(call.callId())) {
                throw new IllegalStateException("template endpoint unavailable");
            }
            return TemplateExecutionLayer.Invocation.completed(execution(call.callId()));
        });

        assertThat(invoked).containsExactly("first", "second", "third");
        assertThat(attempts).extracting(TemplateExecutionLayer.Attempt::completed)
            .containsExactly(true, false, true);
        assertThat(attempts.get(1).errorCode()).isEqualTo("TEMPLATE_CHILD_RUNTIME_ERROR");
        assertThat(attempts.get(1).message()).isEqualTo("template endpoint unavailable");
    }

    @Test
    void keepsOneGovernedResultSlotPerTemplateAfterTerminalCondition() {
        List<TemplateExecutionLayer.Attempt> attempts = layer.execute(
            List.of(call("first"), call("second"), call("third")),
            (call, index) -> TemplateExecutionLayer.Invocation.terminal(
                "TIME_BUDGET_EXHAUSTED", "TIME_BUDGET_EXHAUSTED", "deadline exhausted",
                "BATCH_DEADLINE_EXHAUSTED", "not executed after deadline"));

        assertThat(attempts).extracting(TemplateExecutionLayer.Attempt::status)
            .containsExactly("TIME_BUDGET_EXHAUSTED", "NOT_EXECUTED", "NOT_EXECUTED");
    }

    private ToolCallRequest call(String callId) {
        return new ToolCallRequest(callId, "template_executor", Map.of("templateId", callId));
    }

    private ToolRuntimeExecution execution(String outcome) {
        return new ToolRuntimeExecution(null, null, null, outcome, Map.of());
    }
}
