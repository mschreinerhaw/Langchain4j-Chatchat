package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRunEntity, String> {

    List<ScheduledTaskRunEntity> findByScheduledTaskIdOrderByFireTimeDesc(String scheduledTaskId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByTenantIdAndAgentIdOrderByFireTimeDesc(String tenantId, String agentId, Pageable pageable);

    List<ScheduledTaskRunEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    Optional<ScheduledTaskRunEntity> findFirstByTaskIdOrderByFireTimeDesc(String taskId);

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
}
