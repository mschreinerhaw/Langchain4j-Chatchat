package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TodoTaskRepository extends JpaRepository<TodoTaskEntity, String> {

    Optional<TodoTaskEntity> findByTenantIdAndTaskIdAndTodoType(String tenantId, String taskId, String todoType);

    List<TodoTaskEntity> findByTenantIdAndStatusInOrderByPriorityDescCreatedAtAsc(
        String tenantId,
        Collection<String> statuses,
        Pageable pageable
    );

    List<TodoTaskEntity> findByTenantIdAndUserIdAndStatusInOrderByPriorityDescCreatedAtAsc(
        String tenantId,
        String userId,
        Collection<String> statuses,
        Pageable pageable
    );
}
