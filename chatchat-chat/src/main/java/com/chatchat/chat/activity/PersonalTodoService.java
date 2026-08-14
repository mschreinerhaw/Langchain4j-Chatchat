package com.chatchat.chat.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonalTodoService {

    private final PersonalTodoRepository repository;

    @Transactional(readOnly = true)
    public List<TodoItem> list(String tenantId, String userId, boolean includeCompleted, int limit) {
        String tenant = requireText(tenantId, "Tenant ID cannot be empty");
        String user = requireText(userId, "User ID cannot be empty");
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 200)));
        List<PersonalTodoEntity> items = includeCompleted
            ? repository.findByTenantIdAndUserIdOrderByCompletedAscImportantDescUpdatedAtDesc(tenant, user, page)
            : repository.findByTenantIdAndUserIdAndCompletedFalseOrderByImportantDescUpdatedAtDesc(tenant, user, page);
        return items.stream().map(this::toItem).toList();
    }

    @Transactional
    public TodoItem create(TodoCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Todo payload cannot be empty");
        }
        PersonalTodoEntity entity = new PersonalTodoEntity();
        entity.setTenantId(requireText(request.tenantId(), "Tenant ID cannot be empty"));
        entity.setUserId(requireText(request.userId(), "User ID cannot be empty"));
        entity.setTitle(truncate(requireText(request.title(), "Todo title cannot be empty"), 300));
        entity.setNotes(truncate(normalizeText(request.notes()), 2000));
        entity.setDueAt(request.dueAt());
        entity.setImportant(request.important());
        return toItem(repository.save(entity));
    }

    @Transactional
    public TodoItem update(String todoId, TodoUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Todo update payload cannot be empty");
        }
        PersonalTodoEntity entity = owned(todoId, request.tenantId(), request.userId());
        if (request.title() != null) {
            entity.setTitle(truncate(requireText(request.title(), "Todo title cannot be empty"), 300));
        }
        if (request.notes() != null) {
            entity.setNotes(truncate(normalizeText(request.notes()), 2000));
        }
        if (request.dueAtChanged()) {
            entity.setDueAt(request.dueAt());
        }
        if (request.completed() != null) {
            entity.setCompleted(request.completed());
        }
        if (request.important() != null) {
            entity.setImportant(request.important());
        }
        return toItem(repository.save(entity));
    }

    @Transactional
    public void delete(String todoId, String tenantId, String userId) {
        repository.delete(owned(todoId, tenantId, userId));
    }

    private PersonalTodoEntity owned(String todoId, String tenantId, String userId) {
        return repository.findByIdAndTenantIdAndUserId(
            requireText(todoId, "Todo ID cannot be empty"),
            requireText(tenantId, "Tenant ID cannot be empty"),
            requireText(userId, "User ID cannot be empty")
        ).orElseThrow(() -> new IllegalArgumentException("Todo does not exist for the current user"));
    }

    private TodoItem toItem(PersonalTodoEntity entity) {
        return new TodoItem(
            entity.getId(), entity.getTitle(), entity.getNotes(), entity.getDueAt(), entity.isCompleted(),
            entity.isImportant(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private String requireText(String value, String message) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record TodoItem(
        String id,
        String title,
        String notes,
        Instant dueAt,
        boolean completed,
        boolean important,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record TodoCreateRequest(
        String tenantId,
        String userId,
        String title,
        String notes,
        Instant dueAt,
        boolean important
    ) {
    }

    public record TodoUpdateRequest(
        String tenantId,
        String userId,
        String title,
        String notes,
        Instant dueAt,
        boolean dueAtChanged,
        Boolean completed,
        Boolean important
    ) {
    }
}
