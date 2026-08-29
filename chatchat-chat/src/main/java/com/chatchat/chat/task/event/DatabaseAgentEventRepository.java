package com.chatchat.chat.task.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatabaseAgentEventRepository extends JpaRepository<DatabaseAgentEventEntity, String> {

    List<DatabaseAgentEventEntity> findByTenantIdAndSessionIdAndTaskIdOrderBySequenceAscCreatedAtAsc(
        String tenantId, String sessionId, String taskId, Pageable pageable);

    List<DatabaseAgentEventEntity> findByTenantIdAndSessionIdAndTaskIdAndSequenceGreaterThanOrderBySequenceAscCreatedAtAsc(
        String tenantId, String sessionId, String taskId, long sequence, Pageable pageable);

    Optional<DatabaseAgentEventEntity> findTopByTenantIdAndSessionIdAndTaskIdOrderBySequenceDesc(
        String tenantId, String sessionId, String taskId);
}
