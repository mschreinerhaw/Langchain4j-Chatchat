package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRuntimeTaskEventPublisherTest {

    @Test
    void bridgesRuntimeStepToTaskEventStore() {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            eventBus,
            new ObjectMapper()
        );
        AgentTaskLatestEntity task = task("task-runtime-001");
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        eventStore.save(AgentEvent.builder()
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("QUESTION")
            .status("PENDING")
            .sequence(1L)
            .payload("{}")
            .build());

        publisher.publish(AgentRunEvent.of(
            task.getTaskId(),
            AgentRunEventType.STEP_RECORDED,
            "Agent step recorded",
            Map.of(
                "step", 1,
                "action", "tool",
                "toolName", "document_search"
            )
        ));

        List<AgentEvent> events = eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), 10);
        assertThat(events).hasSize(2);
        AgentEvent runtimeEvent = events.get(1);
        assertThat(runtimeEvent.getType()).isEqualTo("RUNTIME_STEP");
        assertThat(runtimeEvent.getStatus()).isEqualTo("RUNNING");
        assertThat(runtimeEvent.getToolName()).isEqualTo("document_search");
        assertThat(runtimeEvent.getParentEventId()).isEqualTo(events.get(0).getEventId());
        assertThat(runtimeEvent.getPayload())
            .contains("STEP_RECORDED")
            .contains("document_search");
        verify(eventBus).publishResult(runtimeEvent);
    }

    @Test
    void ignoresRuntimeEventsWithoutMatchingTask() {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            mock(AgentEventBus.class),
            new ObjectMapper()
        );
        when(latestRepository.findById("standalone-run")).thenReturn(Optional.empty());

        publisher.publish(AgentRunEvent.of(
            "standalone-run",
            AgentRunEventType.RUN_STARTED,
            "Agent run started",
            Map.of()
        ));

        verifyNoInteractions(eventStore);
    }

    private AgentTaskLatestEntity task(String taskId) {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId(taskId);
        task.setTenantId("tenant-runtime");
        task.setUserId("user-runtime");
        task.setAgentId("general");
        task.setSessionId("session-runtime");
        task.setStatus("RUNNING");
        task.setQuestion("runtime bridge");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        return task;
    }
}
