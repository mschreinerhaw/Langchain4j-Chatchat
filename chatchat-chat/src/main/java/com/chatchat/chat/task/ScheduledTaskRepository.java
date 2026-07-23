package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    @Query("""
        select task from ScheduledTaskEntity task
        where task.tenantId = :tenantId
          and (:userId = '' or task.userId = :userId)
          and (:agentId = '' or task.agentId = :agentId)
          and (
            :status = ''
            or (:status = 'SCHEDULE_ERROR' and task.lastTaskStatus = 'TRADING_DAY_CHECK_FAILED')
            or (:status = 'SKIPPED_NON_TRADING_DAY' and task.lastTaskStatus = 'SKIPPED_NON_TRADING_DAY')
            or (:status = 'AGENT_AUTHORIZATION_DENIED' and task.lastTaskStatus = 'AGENT_AUTHORIZATION_DENIED')
            or (:status = 'AGENT_UNPUBLISHED' and task.lastTaskStatus = 'AGENT_UNPUBLISHED')
            or task.status = :status
          )
          and (
            :keyword = ''
            or lower(coalesce(task.name, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(task.question, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(task.agentId, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(task.lastError, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(task.cronExpr, '')) like lower(concat('%', :keyword, '%'))
            or task.agentId in :keywordAgentIds
          )
        order by task.createdAt desc
        """)
    Page<ScheduledTaskEntity> search(
        @Param("tenantId") String tenantId,
        @Param("userId") String userId,
        @Param("agentId") String agentId,
        @Param("status") String status,
        @Param("keyword") String keyword,
        @Param("keywordAgentIds") List<String> keywordAgentIds,
        Pageable pageable
    );

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
