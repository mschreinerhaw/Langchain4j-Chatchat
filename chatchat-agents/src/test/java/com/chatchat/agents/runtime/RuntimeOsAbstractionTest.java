package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.event.RuntimeEvent;
import com.chatchat.agents.runtime.event.RuntimeEventJournal;
import com.chatchat.agents.runtime.event.RuntimeEventPublisher;
import com.chatchat.agents.runtime.workflow.AbstractRuntimeWorkflow;
import com.chatchat.agents.runtime.workflow.RuntimeWorkflow;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOsAbstractionTest {

    @Test
    void workflowTemplateOwnsLifecycleOrdering() {
        List<String> lifecycle = new ArrayList<>();
        RuntimeWorkflow<String, String> workflow = new AbstractRuntimeWorkflow<>() {
            @Override protected void validateInput(String input) { lifecycle.add("validate:" + input); }
            @Override protected void beforeExecution(String input) { lifecycle.add("before:" + input); }
            @Override protected String doExecute(String input) {
                lifecycle.add("execute:" + input);
                return input.toUpperCase();
            }
            @Override protected void afterExecution(String input, String output) { lifecycle.add("after:" + output); }
        };

        assertThat(workflow.execute("plan")).isEqualTo("PLAN");
        assertThat(lifecycle).containsExactly("validate:plan", "before:plan", "execute:plan", "after:PLAN");
    }

    @Test
    void workflowTemplateReportsFailureWithoutSwallowingIt() {
        List<String> lifecycle = new ArrayList<>();
        RuntimeWorkflow<String, String> workflow = new AbstractRuntimeWorkflow<>() {
            @Override protected String doExecute(String input) { throw new IllegalStateException("failed"); }
            @Override protected void onExecutionFailure(String input, Throwable error) {
                lifecycle.add(input + ":" + error.getMessage());
            }
        };

        assertThatThrownBy(() -> workflow.execute("plan"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failed");
        assertThat(lifecycle).containsExactly("plan:failed");
    }

    @Test
    void concreteRuntimeTypesImplementStablePorts() {
        assertThat(RuntimeWorkflow.class).isAssignableFrom(AgentRunExecutor.class);
        assertThat(RuntimeWorkflow.class).isAssignableFrom(InterpretationPlanRuntime.class);
        assertThat(RuntimeEvent.class).isAssignableFrom(AgentRunEvent.class);
        assertThat(RuntimeEventPublisher.class).isAssignableFrom(AgentRunEventPublisher.class);
        assertThat(RuntimeEventJournal.class).isAssignableFrom(AgentRunStore.class);
        assertThat(AbstractAgentRunStore.class).isAssignableFrom(InMemoryAgentRunStore.class);
    }

    @Test
    void defaultEventBusFansOutAndIsolatesSubscriberFailures() {
        List<AgentRunEvent> received = new ArrayList<>();
        DefaultAgentRunEventBus bus = new DefaultAgentRunEventBus(List.of(
            event -> { throw new IllegalStateException("subscriber unavailable"); },
            received::add
        ));
        AgentRunEvent event = AgentRunEvent.of("run-1", AgentRunEventType.RUN_STARTED,
            "started", Map.of("tenant", "tenant-1"));

        bus.publish(event);

        assertThat(received).containsExactly(event);
        assertThat(event.streamId()).isEqualTo("run-1");
        assertThat(event.eventType()).isEqualTo("RUN_STARTED");
        assertThat(event.occurredAt()).isEqualTo(event.createdAt());
    }
}
