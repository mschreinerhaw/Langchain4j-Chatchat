package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    List<ScheduledTaskEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    List<ScheduledTaskEntity> findByTenantIdAndAgentIdOrderByCreatedAtDesc(String tenantId, String agentId, Pageable pageable);

    List<ScheduledTaskEntity> findByStatusAndNextFireTimeLessThanEqualOrderByNextFireTimeAsc(
        String status,
        Instant nextFireTime,
        Pageable pageable
    );

    List<ScheduledTaskEntity> findByStatusAndExpiredAtBeforeOrderByExpiredAtAsc(
        String status,
        Instant expiredAt,
        Pageable pageable
    );

    List<ScheduledTaskEntity> findByStatusAndLastTaskIdIsNotNullOrderByUpdatedAtAsc(String status, Pageable pageable);
}
