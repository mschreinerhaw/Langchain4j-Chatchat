package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update ScheduledTaskEntity task
           set task.status = 'RUNNING', task.nextFireTime = null
         where task.taskId = :taskId
           and task.status = 'ACTIVE'
           and task.nextFireTime <= :now
        """)
    int claimDueTask(@Param("taskId") String taskId, @Param("now") Instant now);

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

    Optional<ScheduledTaskEntity> findFirstByLastTaskId(String lastTaskId);
}
