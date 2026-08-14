package com.chatchat.chat.activity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalTodoServiceTest {

    private final PersonalTodoRepository repository = mock(PersonalTodoRepository.class);
    private final PersonalTodoService service = new PersonalTodoService(repository);

    @Test
    void createsPersonalTodoForCurrentTenantAndUser() {
        when(repository.save(any(PersonalTodoEntity.class))).thenAnswer(invocation -> {
            PersonalTodoEntity entity = invocation.getArgument(0);
            entity.onCreate();
            return entity;
        });

        PersonalTodoService.TodoItem item = service.create(new PersonalTodoService.TodoCreateRequest(
            "tenant-1", "alice", "整理周报", "周五前完成", Instant.parse("2026-08-15T09:00:00Z"), true
        ));

        assertThat(item.title()).isEqualTo("整理周报");
        assertThat(item.notes()).isEqualTo("周五前完成");
        assertThat(item.important()).isTrue();
        assertThat(item.completed()).isFalse();
    }

    @Test
    void updatesOnlyTodoOwnedByCurrentUser() {
        PersonalTodoEntity entity = new PersonalTodoEntity();
        entity.setId("todo-1");
        entity.setTenantId("tenant-1");
        entity.setUserId("alice");
        entity.setTitle("旧任务");
        entity.onCreate();
        when(repository.findByIdAndTenantIdAndUserId("todo-1", "tenant-1", "alice"))
            .thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);

        PersonalTodoService.TodoItem item = service.update("todo-1", new PersonalTodoService.TodoUpdateRequest(
            "tenant-1", "alice", "新任务", null, null, false, true, null
        ));

        assertThat(item.title()).isEqualTo("新任务");
        assertThat(item.completed()).isTrue();
    }
}
