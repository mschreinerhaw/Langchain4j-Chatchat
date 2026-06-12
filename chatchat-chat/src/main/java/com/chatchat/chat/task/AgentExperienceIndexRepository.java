package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentExperienceIndexRepository extends JpaRepository<AgentExperienceIndexEntity, String> {

    Optional<AgentExperienceIndexEntity> findByTenantIdAndIndexKey(String tenantId, String indexKey);

    List<AgentExperienceIndexEntity> findByTenantIdOrderBySuccessRateDescUpdatedAtDesc(String tenantId, Pageable pageable);

    @Query("""
        select e from AgentExperienceIndexEntity e
        where e.tenantId = :tenantId
          and (:agentId is null or e.agentId = :agentId)
          and (:scenario is null or e.scenario = :scenario)
          and (:intentType is null or e.intentType = :intentType)
        order by e.successRate desc, e.sampleCount desc, e.updatedAt desc
        """)
    List<AgentExperienceIndexEntity> findRuntimeCandidates(@Param("tenantId") String tenantId,
                                                           @Param("agentId") String agentId,
                                                           @Param("scenario") String scenario,
                                                           @Param("intentType") String intentType,
                                                           Pageable pageable);

    long countByTenantId(String tenantId);

    void deleteByTenantId(String tenantId);
}
