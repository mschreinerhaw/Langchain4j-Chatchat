package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentTaskLatestRepository extends JpaRepository<AgentTaskLatestEntity, String> {

    /**
     * Finds the all by order by create time desc.
     *
     * @param pageable the pageable value
     * @return the matching all by order by create time desc
     */
    List<AgentTaskLatestEntity> findAllByOrderByCreateTimeDesc(Pageable pageable);

    /**
     * Finds the by tenant id order by create time desc.
     *
     * @param tenantId the tenant id value
     * @param pageable the pageable value
     * @return the matching by tenant id order by create time desc
     */
    List<AgentTaskLatestEntity> findByTenantIdOrderByCreateTimeDesc(String tenantId, Pageable pageable);

    /**
     * Finds the by tenant id and session id order by create time desc.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param pageable the pageable value
     * @return the matching by tenant id and session id order by create time desc
     */
    List<AgentTaskLatestEntity> findByTenantIdAndSessionIdOrderByCreateTimeDesc(String tenantId, String sessionId, Pageable pageable);

    /**
     * Finds the by status in order by create time asc.
     *
     * @param statuses the statuses value
     * @return the matching by status in order by create time asc
     */
    List<AgentTaskLatestEntity> findByStatusInOrderByCreateTimeAsc(List<String> statuses);

    /**
     * Finds the by tenant id and status in order by create time asc.
     *
     * @param tenantId the tenant id value
     * @param statuses the statuses value
     * @return the matching by tenant id and status in order by create time asc
     */
    List<AgentTaskLatestEntity> findByTenantIdAndStatusInOrderByCreateTimeAsc(String tenantId, List<String> statuses);

    /**
     * Performs the count by status in operation.
     *
     * @param statuses the statuses value
     * @return the operation result
     */
    long countByStatusIn(List<String> statuses);

    /**
     * Performs the count by tenant id and status in operation.
     *
     * @param tenantId the tenant id value
     * @param statuses the statuses value
     * @return the operation result
     */
    long countByTenantIdAndStatusIn(String tenantId, List<String> statuses);

    /**
     * Finds the by update time before and status in.
     *
     * @param updateTime the update time value
     * @param statuses the statuses value
     * @return the matching by update time before and status in
     */
    List<AgentTaskLatestEntity> findByUpdateTimeBeforeAndStatusIn(Instant updateTime, List<String> statuses);

    /**
     * Performs the count all by status operation.
     *
     * @return the operation result
     */
    @Query("select t.status as status, count(t) as total from AgentTaskLatestEntity t group by t.status")
    List<StatusCount> countAllByStatus();

    /**
     * Performs the count by tenant id group by status operation.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    @Query("select t.status as status, count(t) as total from AgentTaskLatestEntity t where t.tenantId = :tenantId group by t.status")
    List<StatusCount> countByTenantIdGroupByStatus(@Param("tenantId") String tenantId);

    /**
     * Performs the count distinct tenants operation.
     *
     * @return the operation result
     */
    @Query("select count(distinct t.tenantId) from AgentTaskLatestEntity t")
    long countDistinctTenants();

    /**
     * Performs the count top tenants operation.
     *
     * @param pageable the pageable value
     * @return the operation result
     */
    @Query("select t.tenantId as tenantId, count(t) as total from AgentTaskLatestEntity t group by t.tenantId order by count(t) desc")
    List<TenantCount> countTopTenants(Pageable pageable);

    interface StatusCount {

        /**
         * Returns the status.
         *
         * @return the status
         */
        String getStatus();

        /**
         * Returns the total.
         *
         * @return the total
         */
        long getTotal();
    }

    interface TenantCount {

        /**
         * Returns the tenant id.
         *
         * @return the tenant id
         */
        String getTenantId();

        /**
         * Returns the total.
         *
         * @return the total
         */
        long getTotal();
    }
}
