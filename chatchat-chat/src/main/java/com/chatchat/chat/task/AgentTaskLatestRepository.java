package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentTaskLatestRepository extends JpaRepository<AgentTaskLatestEntity, String> {

    List<AgentTaskLatestEntity> findAllByOrderByCreateTimeDesc(Pageable pageable);

    List<AgentTaskLatestEntity> findByTenantIdOrderByCreateTimeDesc(String tenantId, Pageable pageable);

    List<AgentTaskLatestEntity> findByTenantIdAndSessionIdOrderByCreateTimeDesc(String tenantId, String sessionId, Pageable pageable);

    List<AgentTaskLatestEntity> findByStatusInOrderByCreateTimeAsc(List<String> statuses);

    List<AgentTaskLatestEntity> findByTenantIdAndStatusInOrderByCreateTimeAsc(String tenantId, List<String> statuses);

    long countByStatusIn(List<String> statuses);

    long countByTenantIdAndStatusIn(String tenantId, List<String> statuses);

    List<AgentTaskLatestEntity> findByUpdateTimeBeforeAndStatusIn(Instant updateTime, List<String> statuses);

    @Query("select t.status as status, count(t) as total from AgentTaskLatestEntity t group by t.status")
    List<StatusCount> countAllByStatus();

    @Query("select t.status as status, count(t) as total from AgentTaskLatestEntity t where t.tenantId = :tenantId group by t.status")
    List<StatusCount> countByTenantIdGroupByStatus(@Param("tenantId") String tenantId);

    @Query("select count(distinct t.tenantId) from AgentTaskLatestEntity t")
    long countDistinctTenants();

    @Query("select t.tenantId as tenantId, count(t) as total from AgentTaskLatestEntity t group by t.tenantId order by count(t) desc")
    List<TenantCount> countTopTenants(Pageable pageable);

    interface StatusCount {

        String getStatus();

        long getTotal();
    }

    interface TenantCount {

        String getTenantId();

        long getTotal();
    }
}
