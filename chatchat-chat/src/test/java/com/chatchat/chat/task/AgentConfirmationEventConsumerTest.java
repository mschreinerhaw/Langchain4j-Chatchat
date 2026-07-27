package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentConfirmationEventConsumerTest {

    @Test
    void delegatesConfirmationEventToTaskStateMachine() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentTaskService taskService = mock(AgentTaskService.class);
        AgentConfirmationEventConsumer consumer = new AgentConfirmationEventConsumer(eventBus, taskService);
        AgentEvent event = AgentEvent.builder()
            .taskId("task-1")
            .tenantId("tenant-1")
            .type("CONFIRMATION")
            .status("PENDING")
            .build();

        consumer.consume(event);

        verify(taskService).consumeConfirmationEvent(event);
    }
}
