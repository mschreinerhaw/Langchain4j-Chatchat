package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureIsolatedBatchExecutionLayerTest {

    private final FailureIsolatedBatchExecutionLayer layer = new FailureIsolatedBatchExecutionLayer();

    @Test void cancellationPropagatesAndDoesNotInvokeLaterChildren() {
        List<String> invoked = new ArrayList<>();
        assertThatThrownBy(() -> layer.execute(List.of(call("first"), call("second")), (call,index) -> {
            invoked.add(call.callId()); throw new java.util.concurrent.CancellationException();
        })).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(invoked).containsExactly("first");
    }

    @Test void parallelCancellationPropagatesInsteadOfBecomingAFailedChild() {
        assertThatThrownBy(() -> layer.executeParallelReadOnly(List.of(call("first")), (call,index) -> {
            throw new java.util.concurrent.CancellationException();
        })).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test void interruptedParallelWaitReturnsWithInterruptFlagPreserved() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                worker.set(Thread.currentThread());
                try {
                    layer.executeParallelReadOnly(List.of(call("first")), (call,index) -> {
                        entered.countDown();
                        try { release.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                        return FailureIsolatedBatchExecutionLayer.Invocation.completed(execution(call.callId()));
                    });
                    return false;
                } catch (java.util.concurrent.CancellationException cancelled) { return Thread.currentThread().isInterrupted(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); worker.get().interrupt();
            assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

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

    @Test
    void parallelReadOnlyExecutionStartsIndependentChildrenTogetherAndKeepsOrder() throws Exception {
        List<ToolCallRequest> calls = List.of(call("first"), call("second"), call("third"));
        CountDownLatch started = new CountDownLatch(calls.size());
        CountDownLatch release = new CountDownLatch(1);

        var future = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            layer.executeParallelReadOnly(calls, (call, index) -> {
                started.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("parallel siblings did not start");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return FailureIsolatedBatchExecutionLayer.Invocation.completed(execution(call.callId()));
            }));

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(future.get(2, TimeUnit.SECONDS))
            .extracting(FailureIsolatedBatchExecutionLayer.Attempt::call)
            .extracting(ToolCallRequest::callId)
            .containsExactly("first", "second", "third");
    }

    private ToolCallRequest call(String callId) {
        return new ToolCallRequest(callId, "generic_executor", Map.of("contractId", callId));
    }

    private ToolRuntimeExecution execution(String outcome) {
        return new ToolRuntimeExecution(null, null, null, outcome, Map.of());
    }
}
