package com.chatchat.chat.task.queue;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TenantRuntimeQuotaRepository extends JpaRepository<TenantRuntimeQuotaEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from TenantRuntimeQuotaEntity q where q.tenantId = :tenantId")
    Optional<TenantRuntimeQuotaEntity> findForUpdate(@Param("tenantId") String tenantId);

    @Query("""
        select q.tenantId from TenantRuntimeQuotaEntity q
        where q.activeRuns <> (
            select count(t) from AgentTaskLatestEntity t
            where t.tenantId = q.tenantId
              and t.claimToken is not null
              and t.leaseExpiresAt is not null
              and t.leaseExpiresAt >= :now
        )
        order by q.lastDispatchAt asc
        """)
    List<String> findDriftedTenantIds(@Param("now") Instant now, Pageable pageable);
}
