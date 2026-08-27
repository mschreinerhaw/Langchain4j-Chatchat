package com.chatchat.chat.task.event;

import com.chatchat.chat.task.event.InMemoryAgentEventStore;

import com.chatchat.chat.task.event.AgentRuntimeTaskEventPublisher;

import com.chatchat.chat.task.event.AgentEventStore;

import com.chatchat.chat.task.event.AgentEventBus;

import com.chatchat.chat.task.event.AgentEvent;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void concurrentRuntimeCompletionRemainsNonTerminalUntilFinalTaskAnswerIsPublished() throws Exception {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            eventBus,
            objectMapper
        );
        int requestCount = 12;
        Map<String, AgentTaskLatestEntity> tasks = new java.util.LinkedHashMap<>();
        for (int index = 0; index < requestCount; index++) {
            AgentTaskLatestEntity task = task("task-runtime-concurrent-" + index);
            task.setTenantId("tenant-runtime-" + (index % 3));
            task.setSessionId("shared-session-" + (index % 2));
            tasks.put(task.getTaskId(), task);
        }
        when(latestRepository.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(tasks.get(invocation.getArgument(0))));

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = tasks.keySet().stream()
                .map(taskId -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    publisher.publish(AgentRunEvent.of(
                        taskId,
                        AgentRunEventType.RUN_COMPLETED,
                        "Agent Runtime completed",
                        Map.of("answer", "final summary")
                    ));
                    return null;
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        tasks.values().forEach(task -> {
            List<AgentEvent> events = eventStore.listByTask(
                task.getTenantId(), task.getSessionId(), task.getTaskId(), 10);
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.getType()).isEqualTo("RUNTIME_COMPLETED");
                assertThat(event.getStatus()).isEqualTo("RUNNING");
                assertThat(event.getTenantId()).isEqualTo(task.getTenantId());
                assertThat(event.getSessionId()).isEqualTo(task.getSessionId());
                assertThat(event.getTaskId()).isEqualTo(task.getTaskId());
                assertThat(event.getPayload()).doesNotContain("\"answer\":\"final summary\"");
            });
        });
        verify(eventBus, times(requestCount)).publishResult(org.mockito.ArgumentMatchers.any(AgentEvent.class));
    }

    @Test
    void compactsLargeRuntimeObservationBeforePersistingTaskEvent() throws Exception {
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        AgentRuntimeTaskEventPublisher publisher = new AgentRuntimeTaskEventPublisher(
            latestRepository,
            eventStore,
            mock(AgentEventBus.class),
            objectMapper
        );
        AgentTaskLatestEntity task = task("task-runtime-large-observation");
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));

        publisher.publish(AgentRunEvent.of(
            task.getTaskId(),
            AgentRunEventType.OBSERVATION_RECORDED,
            "Agent observation recorded",
            Map.of(
                "type", "tool",
                "source", "enterprise_metadata_search",
                "contentPreview", "68 fields matched",
                "metadata", Map.of("evidence", "x".repeat(300_000))
            )
        ));

        AgentEvent event = eventStore.listByTask(
            task.getTenantId(), task.getSessionId(), task.getTaskId(), 10
        ).get(0);
        Map<String, Object> envelope = objectMapper.readValue(event.getPayload(), Map.class);
        assertThat(event.getPayload().length()).isLessThan(70_000);
        assertThat(envelope.get("payload"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("source", "enterprise_metadata_search")
            .containsEntry("contentPreview", "68 fields matched")
            .containsEntry("eventPayloadCompacted", true);
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
