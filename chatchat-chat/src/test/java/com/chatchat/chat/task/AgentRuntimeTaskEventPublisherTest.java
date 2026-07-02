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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bridgesRuntimeStepToTaskEventStore() {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            eventBus,
            objectMapper
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
            objectMapper
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

    @Test
    void bridgesRuntimeFailedEventToFailedTaskEvent() throws Exception {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            eventBus,
            objectMapper
        );
        AgentTaskLatestEntity task = task("task-runtime-failed-001");
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        publisher.publish(AgentRunEvent.of(
            task.getTaskId(),
            AgentRunEventType.RUN_FAILED,
            "Agent run failed",
            Map.of(
                "errorCode", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED",
                "errorMessage", "mandatory workflow incomplete",
                "answer", "必需工具 sql_query_execute 未执行完成，无法生成最终分析。"
            )
        ));

        List<AgentEvent> events = eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), 10);
        assertThat(events).hasSize(1);
        AgentEvent failedEvent = events.get(0);
        assertThat(failedEvent.getType()).isEqualTo("RUNTIME_FAILED");
        assertThat(failedEvent.getStatus()).isEqualTo("FAILED");
        assertThat(failedEvent.getErrorCode()).isEqualTo("PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED");
        Map<String, Object> payload = objectMapper.readValue(failedEvent.getPayload(), Map.class);
        assertThat(payload)
            .containsEntry("status", "FAILED")
            .containsEntry("answer", "必需工具 sql_query_execute 未执行完成，无法生成最终分析。");
        assertThat(payload.get("message")).isEqualTo("必需工具 sql_query_execute 未执行完成，无法生成最终分析。");
        assertThat(payload.get("uiResponse")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("answer", "必需工具 sql_query_execute 未执行完成，无法生成最终分析。")
            .containsEntry("status", "FAILED");
        verify(eventBus).publishResult(failedEvent);
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
