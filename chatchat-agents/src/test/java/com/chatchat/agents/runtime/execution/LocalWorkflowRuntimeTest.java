package com.chatchat.agents.runtime.execution;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalWorkflowRuntimeTest {

    @Test
    void sharesOneExecutionForDuplicateIdempotentStarts() {
        QueuedExecutor executor = new QueuedExecutor();
        LocalWorkflowRuntime runtime = new LocalWorkflowRuntime(executor, new AgentRuntimeProperties());
        WorkflowStartRequest<String> request = new WorkflowStartRequest<>(
            "workflow-1", "agent-run-v1", "tenant-1", "tenant-1:request-1", "input");
        AtomicInteger executions = new AtomicInteger();
        runtime.register("agent-run-v1", String.class, String.class, (input, context) -> {
            executions.incrementAndGet();
            return input + "-done";
        });

        WorkflowHandle<String> first = runtime.start(request);
        WorkflowHandle<String> duplicate = runtime.start(request);

        assertThat(first.newlyStarted()).isTrue();
        assertThat(duplicate.newlyStarted()).isFalse();
        assertThat(executor.size()).isEqualTo(1);
        assertThat(runtime.activeExecutionCount()).isEqualTo(1);

        executor.runNext();

        assertThat(first.completion().join()).isEqualTo("input-done");
        assertThat(duplicate.completion().join()).isEqualTo("input-done");
        assertThat(executions).hasValue(1);
        assertThat(runtime.activeExecutionCount()).isZero();
    }

    @Test
    void rejectsWorkflowIdReuseWithDifferentBusinessIdentity() {
        QueuedExecutor executor = new QueuedExecutor();
        LocalWorkflowRuntime runtime = new LocalWorkflowRuntime(executor, new AgentRuntimeProperties());
        runtime.register("agent-run-v1", String.class, String.class, (input, context) -> input);
        runtime.start(new WorkflowStartRequest<>(
            "workflow-2", "agent-run-v1", "tenant-1", "tenant-1:request-1", "input"));

        assertThatThrownBy(() -> runtime.start(new WorkflowStartRequest<>(
            "workflow-2", "agent-run-v1", "tenant-1", "tenant-1:request-2", "input")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("different type, tenant or idempotency key");
    }

    @Test
    void cancellationBeforeExecutionPreventsWorkflowBodyFromRunning() {
        QueuedExecutor executor = new QueuedExecutor();
        LocalWorkflowRuntime runtime = new LocalWorkflowRuntime(executor, new AgentRuntimeProperties());
        AtomicInteger executions = new AtomicInteger();
        runtime.register("agent-run-v1", String.class, String.class, (input, context) -> {
            executions.incrementAndGet();
            return input;
        });
        WorkflowHandle<String> handle = runtime.start(new WorkflowStartRequest<>(
            "workflow-3", "agent-run-v1", "tenant-1", "tenant-1:request-3", "input"));

        assertThat(runtime.cancel("workflow-3", "cancelled by user")).isTrue();
        executor.runNext();

        assertThatThrownBy(() -> handle.completion().join())
            .isInstanceOf(CancellationException.class);
        assertThat(executions).hasValue(0);
        assertThat(runtime.activeExecutionCount()).isZero();
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            assertThat(tasks).isNotEmpty();
            tasks.remove().run();
        }
    }
}
