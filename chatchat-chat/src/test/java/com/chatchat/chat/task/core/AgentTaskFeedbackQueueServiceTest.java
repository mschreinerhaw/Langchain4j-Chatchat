package com.chatchat.chat.task.core;

import com.chatchat.chat.task.core.AgentTaskService;

import com.chatchat.chat.task.core.AgentTaskResponse;

import com.chatchat.chat.task.core.AgentTaskProperties;

import com.chatchat.chat.task.core.AgentTaskFeedbackRequest;

import com.chatchat.chat.task.core.AgentTaskFeedbackQueueService;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskFeedbackQueueServiceTest {

    @Test
    void rejectsOversizedFeedbackBeforePersistenceOrWorkerCreation() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AgentTaskFeedbackRequest request = new AgentTaskFeedbackRequest();
        request.setComment("x".repeat(4_001));
        AgentTaskFeedbackQueueService service = new AgentTaskFeedbackQueueService(
            taskService, new AgentTaskProperties(), executor);

        assertThatThrownBy(() -> service.enqueueFeedback("tenant-1", "task-1", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("4000");
        verify(taskService, never()).persistFeedback(any(), any(), any());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void persistsFeedbackBeforeQueuingExperienceAttribution() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        AgentTaskProperties properties = new AgentTaskProperties();
        TaskExecutor executor = mock(TaskExecutor.class);
        AgentTaskResponse persisted = mock(AgentTaskResponse.class);
        AgentTaskFeedbackRequest request = new AgentTaskFeedbackRequest();
        request.setUseful(true);
        when(taskService.persistFeedback("tenant-1", "task-1", request)).thenReturn(persisted);
        AgentTaskFeedbackQueueService service = new AgentTaskFeedbackQueueService(taskService, properties, executor);

        AgentTaskResponse response = service.enqueueFeedback("tenant-1", "task-1", request);

        assertThat(response).isSameAs(persisted);
        verify(taskService).persistFeedback("tenant-1", "task-1", request);
        verify(executor).execute(any(Runnable.class));
        service.shutdown();
    }
}
