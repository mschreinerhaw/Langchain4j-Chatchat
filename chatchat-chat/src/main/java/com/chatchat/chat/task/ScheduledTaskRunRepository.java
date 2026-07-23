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

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update ScheduledTaskRunEntity run
           set run.status = 'COMPLETING'
         where run.runId = :runId
           and run.status = 'RUNNING'
        """)
    int claimCompletion(@Param("runId") String runId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update ScheduledTaskRunEntity run
           set run.notificationStatus = 'SENDING', run.notificationSentAt = :now
         where run.runId = :runId
           and run.notificationStatus is null
        """)
    int claimNotification(@Param("runId") String runId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update ScheduledTaskRunEntity run
           set run.tenantId = :tenantId, run.userId = :userId
         where run.scheduledTaskId = :scheduledTaskId
        """)
    int updateOwner(
        @Param("scheduledTaskId") String scheduledTaskId,
        @Param("tenantId") String tenantId,
        @Param("userId") String userId
    );

    List<ScheduledTaskRunEntity> findByScheduledTaskIdOrderByFireTimeDesc(String scheduledTaskId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByTenantIdAndAgentIdOrderByFireTimeDesc(String tenantId, String agentId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByTenantIdAndUserIdAndAgentIdOrderByFireTimeDesc(
        String tenantId, String userId, String agentId, Pageable pageable
    );

    List<ScheduledTaskRunEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    Optional<ScheduledTaskRunEntity> findFirstByTaskIdOrderByFireTimeDesc(String taskId);

    boolean existsByScheduledTaskIdAndStatus(String scheduledTaskId, String status);

    @Query("""
        select distinct run.scheduledTaskId from ScheduledTaskRunEntity run
        where run.status = :status
          and run.scheduledTaskId in :scheduledTaskIds
        """)
    List<String> findScheduledTaskIdsByStatus(
        @Param("scheduledTaskIds") List<String> scheduledTaskIds,
        @Param("status") String status
    );

    @Query("""
        select run from ScheduledTaskRunEntity run
        where run.tenantId = :tenantId
          and run.scheduledTaskId = :scheduledTaskId
          and run.notificationStatus is not null
          and (
            :keyword = ''
            or lower(coalesce(run.notificationChannelType, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.notificationChannelName, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.notificationReceiver, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.notificationStatus, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.notificationError, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.taskId, '')) like lower(concat('%', :keyword, '%'))
          )
        order by run.notificationSentAt desc, run.updatedAt desc
        """)
    Page<ScheduledTaskRunEntity> searchNotificationHistory(
        @Param("tenantId") String tenantId,
        @Param("scheduledTaskId") String scheduledTaskId,
        @Param("keyword") String keyword,
        Pageable pageable
    );

    @Query("""
        select run from ScheduledTaskRunEntity run
        left join ScheduledTaskEntity schedule on schedule.taskId = run.scheduledTaskId
        where run.tenantId = :tenantId
          and (:userId = '' or run.userId = :userId)
          and (:agentId = '' or run.agentId = :agentId)
          and (:status = '' or run.status = :status)
          and (
            :keyword = ''
            or lower(coalesce(schedule.name, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.question, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.agentId, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.status, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.taskId, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.errorMessage, '')) like lower(concat('%', :keyword, '%'))
            or lower(coalesce(run.answerSummary, '')) like lower(concat('%', :keyword, '%'))
            or run.agentId in :keywordAgentIds
          )
        order by run.fireTime desc
        """)
    Page<ScheduledTaskRunEntity> searchAudit(
        @Param("tenantId") String tenantId,
        @Param("userId") String userId,
        @Param("agentId") String agentId,
        @Param("status") String status,
        @Param("keyword") String keyword,
        @Param("keywordAgentIds") List<String> keywordAgentIds,
        Pageable pageable
    );
}
