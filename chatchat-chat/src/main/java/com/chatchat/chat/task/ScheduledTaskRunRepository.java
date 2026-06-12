package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, String> {

    List<ScheduledTaskRunEntity> findByScheduledTaskIdOrderByFireTimeDesc(String scheduledTaskId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByTenantIdAndAgentIdOrderByFireTimeDesc(String tenantId, String agentId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    Optional<ScheduledTaskRunEntity> findFirstByTaskIdOrderByFireTimeDesc(String taskId);
}
