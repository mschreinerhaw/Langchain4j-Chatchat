package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

    @Test
    void recoveryMarksTaskWithMissingQuestionEventFailedWithoutAbortingBatch() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-missing-question");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setAgentId("general");
        task.setSessionId("session-1");
        task.setStatus("WAIT_MODEL");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        when(latestRepository.findByStatusInOrderByCreateTimeAsc(any())).thenReturn(List.of(task));
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventStore.findFirstByTaskAndType(
            task.getTenantId(), task.getSessionId(), task.getTaskId(), "QUESTION"))
            .thenReturn(Optional.empty());
        when(eventStore.nextSequence(task.getTenantId(), task.getSessionId(), task.getTaskId())).thenReturn(1L);
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );

        int recovered = service.recoverActiveTasks();

        assertThat(recovered).isZero();
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getErrorMessage()).contains("Question payload not found", task.getTaskId());
        ArgumentCaptor<AgentEvent> eventCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventStore).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(eventCaptor.getValue().getPayload()).contains("TASK_RECOVERY_PAYLOAD_INVALID");
    }

    @Test
    void cleanDisplayAnswerPreservesSqlCodeFence() {
        String answer = """
            ## JDBC SQL 案例

            ```sql
            CREATE TABLE MyUserTable (
              id BIGINT,
              name STRING
            ) WITH (
              'connector' = 'jdbc',
              'url' = 'jdbc:mysql://localhost:3306/mydatabase'
            );
            ```

            来源：[doc://jdbc#chunk=2]
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("```sql")
            .contains("CREATE TABLE MyUserTable")
            .contains("'connector' = 'jdbc'")
            .contains("来源：[doc://jdbc#chunk=2]");
    }

    @Test
    void cleanDisplayAnswerStillRemovesJsonProtocolFence() {
        String answer = """
            ## 结果

            ```json
            {"uiResponse":{"answer":"internal"}}
            ```

            可展示内容
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("可展示内容")
            .doesNotContain("uiResponse")
            .doesNotContain("```json");
    }
}
