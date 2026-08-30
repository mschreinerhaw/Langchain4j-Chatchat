package com.chatchat.runtime.temporal;

import com.chatchat.agents.runtime.workflow.WorkflowExecutionStatus;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalWorkflowRuntimeTest {

    private TestWorkflowEnvironment environment;
    private TemporalWorkflowRuntime runtime;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        TemporalWorkflowProperties properties = new TemporalWorkflowProperties();
        properties.setTaskQueue("runtime-os-test-" + System.nanoTime());
        properties.setActivityStartToCloseSeconds(60);
        properties.setActivityHeartbeatSeconds(1);
        runtime = new TemporalWorkflowRuntime(
            environment.getWorkflowClient(), environment.getWorkerFactory(),
            new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    void executesRegisteredDefinitionAndExposesTerminalSnapshot() throws Exception {
        runtime.register("echo-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value().toUpperCase(), context.attempt()));

        WorkflowHandle<EchoOutput> handle = runtime.start(request("run-success", "echo-v1", "hello"));

        assertThat(handle.newlyStarted()).isTrue();
        assertThat(handle.completion().get(10, TimeUnit.SECONDS))
            .isEqualTo(new EchoOutput("HELLO", 1));
        assertThat(runtime.find("run-success")).get()
            .satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
                assertThat(snapshot.workflowType()).isEqualTo("echo-v1");
                assertThat(snapshot.tenantId()).isEqualTo("tenant-a");
                assertThat(snapshot.idempotencyKey()).isEqualTo("request-a");
            });
    }

    @Test
    void duplicateWorkflowIdAttachesWithoutExecutingDefinitionTwice() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        runtime.register("count-v1", EchoInput.class, EchoOutput.class, (input, context) -> {
            executions.incrementAndGet();
            return new EchoOutput(input.value(), context.attempt());
        });

        WorkflowHandle<EchoOutput> first = runtime.start(request("run-duplicate", "count-v1", "one"));
        assertThat(first.completion().get(10, TimeUnit.SECONDS)).isEqualTo(new EchoOutput("one", 1));
        WorkflowHandle<EchoOutput> duplicate = runtime.start(request("run-duplicate", "count-v1", "one"));

        assertThat(duplicate.newlyStarted()).isFalse();
        assertThat(duplicate.completion().get(10, TimeUnit.SECONDS)).isEqualTo(new EchoOutput("one", 1));
        assertThat(executions).hasValue(1);
    }

    @Test
    void cancellationInterruptsActivityAndCompletesAsCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        runtime.register("wait-v1", EchoInput.class, EchoOutput.class, (input, context) -> {
            entered.countDown();
            while (true) {
                context.checkCancellation();
                Thread.sleep(20);
            }
        });
        WorkflowHandle<EchoOutput> handle = runtime.start(request("run-cancel", "wait-v1", "wait"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runtime.cancel("run-cancel", "test cancellation")).isTrue();
        assertThatThrownBy(() -> handle.completion().get(10, TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
        assertThat(runtime.find("run-cancel")).get()
            .extracting(snapshot -> snapshot.status())
            .isEqualTo(WorkflowExecutionStatus.CANCELLED);
    }

    @Test
    void duplicateWorkflowIdWithDifferentBusinessIdentityIsRejected() throws Exception {
        runtime.register("echo-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-collision", "echo-v1", "one"))
            .completion().get(10, TimeUnit.SECONDS);

        WorkflowStartRequest<EchoInput> collision = new WorkflowStartRequest<>(
            "run-collision", "echo-v1", "tenant-b", "different-request", new EchoInput("two"));

        assertThatThrownBy(() -> runtime.start(collision).completion().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Workflow id already belongs to a different type, tenant or idempotency key: run-collision");
    }

    private WorkflowStartRequest<EchoInput> request(String id, String type, String value) {
        return new WorkflowStartRequest<>(id, type, "tenant-a", "request-a", new EchoInput(value));
    }

    record EchoInput(String value) {
    }

    record EchoOutput(String value, int attempt) {
    }
}
