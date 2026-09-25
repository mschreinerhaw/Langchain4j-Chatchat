package com.chatchat.api.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisEvidenceArchiveRepository extends JpaRepository<AnalysisEvidenceArchiveEntity, String> {
    Optional<AnalysisEvidenceArchiveEntity> findByArchiveIdAndTenantIdAndUserId(
        String archiveId, String tenantId, String userId);
    List<AnalysisEvidenceArchiveEntity> findByTenantIdAndUserIdAndRunIdOrderByCreatedAtEpochMsDesc(
        String tenantId, String userId, String runId, org.springframework.data.domain.Pageable page);
    @Query("select e from AnalysisEvidenceArchiveEntity e where e.indexStatus is null or e.indexStatus = 'PENDING' order by e.createdAtEpochMs asc")
    List<AnalysisEvidenceArchiveEntity> pendingIndex(org.springframework.data.domain.Pageable page);
    @Query("select e from AnalysisEvidenceArchiveEntity e where e.createdAtEpochMs < :cutoff order by e.createdAtEpochMs asc")
    List<AnalysisEvidenceArchiveEntity> expiredBefore(@Param("cutoff") long cutoff,
                                                        org.springframework.data.domain.Pageable page);
}
