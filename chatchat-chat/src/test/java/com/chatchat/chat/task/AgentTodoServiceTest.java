package com.chatchat.chat.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTodoServiceTest {

    private final AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
    private final TodoTaskRepository todoTaskRepository = mock(TodoTaskRepository.class);
    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final AgentEventStore eventStore = mock(AgentEventStore.class);
    private final TaskConfirmRepository taskConfirmRepository = mock(TaskConfirmRepository.class);
    private final AgentTodoService service = new AgentTodoService(
        latestRepository,
        todoTaskRepository,
        taskService,
        eventStore,
        new ObjectMapper(),
        taskConfirmRepository
    );

    @Test
    void listTodosDeduplicatesExistingOpenRuntimeTodos() {
        AgentTaskLatestEntity task = latestTask("task-1", "SUCCESS");
        TodoTaskEntity first = todo("todo-1", "PENDING");
        TodoTaskEntity duplicate = todo("todo-2", "PENDING");
        when(latestRepository.findByTenantIdOrderByCreateTimeDesc(eq("admin"), any(Pageable.class)))
            .thenReturn(List.of(task));
        when(todoTaskRepository.findByTenantIdAndTaskIdAndTodoTypeOrderByCreatedAtAsc(anyString(), anyString(), anyString()))
            .thenReturn(List.of());
        when(todoTaskRepository.findByTenantIdAndTaskIdAndTodoTypeOrderByCreatedAtAsc(
            "admin",
            "task-1",
            "FEEDBACK_REQUIRED"
        )).thenReturn(List.of(first, duplicate));
        when(todoTaskRepository.save(any(TodoTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(todoTaskRepository.findByTenantIdAndStatusInOrderByPriorityDescCreatedAtAsc(
            eq("admin"),
            anyCollection(),
            any(Pageable.class)
        )).thenReturn(List.of(first));

        AgentTodoService.TodoTaskPayload payload = service.listTodos("admin", null, 20);

        assertThat(payload.total()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(duplicate.getStatus()).isEqualTo("CANCELLED");
        ArgumentCaptor<Iterable<TodoTaskEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(todoTaskRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(duplicate);
    }

    private AgentTaskLatestEntity latestTask(String taskId, String status) {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTenantId("admin");
        task.setTaskId(taskId);
        task.setUserId("u-1");
        task.setAgentId("agent");
        task.setSessionId("session-1");
        task.setStatus(status);
        task.setQuestion("question");
        return task;
    }

    private TodoTaskEntity todo(String id, String status) {
        TodoTaskEntity todo = new TodoTaskEntity();
        todo.setId(id);
        todo.setTenantId("admin");
        todo.setUserId("u-1");
        todo.setTaskId("task-1");
        todo.setAgentId("agent");
        todo.setTodoType("FEEDBACK_REQUIRED");
        todo.setTitle("Feedback");
        todo.setPriority("MEDIUM");
        todo.setStatus(status);
        todo.setCreatedAt(Instant.now());
        todo.setUpdatedAt(Instant.now());
        return todo;
    }
}
