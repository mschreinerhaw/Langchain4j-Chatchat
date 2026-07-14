package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskFeedbackQueueServiceTest {

    @Test
    void persistsFeedbackBeforeQueuingExperienceAttribution() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        AgentTaskProperties properties = new AgentTaskProperties();
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AgentTaskResponse persisted = mock(AgentTaskResponse.class);
        AgentTaskFeedbackRequest request = new AgentTaskFeedbackRequest();
        request.setUseful(true);
        when(taskService.persistFeedback("tenant-1", "task-1", request)).thenReturn(persisted);
        when(executor.submit(any(Runnable.class))).thenReturn(null);
        AgentTaskFeedbackQueueService service = new AgentTaskFeedbackQueueService(taskService, properties, executor);

        AgentTaskResponse response = service.enqueueFeedback("tenant-1", "task-1", request);

        assertThat(response).isSameAs(persisted);
        verify(taskService).persistFeedback("tenant-1", "task-1", request);
        verify(executor).submit(any(Runnable.class));
        service.shutdown();
    }
}
