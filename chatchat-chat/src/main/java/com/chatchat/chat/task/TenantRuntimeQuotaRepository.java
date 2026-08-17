package com.chatchat.chat.task;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TenantRuntimeQuotaRepository extends JpaRepository<TenantRuntimeQuotaEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from TenantRuntimeQuotaEntity q where q.tenantId = :tenantId")
    Optional<TenantRuntimeQuotaEntity> findForUpdate(@Param("tenantId") String tenantId);
}
