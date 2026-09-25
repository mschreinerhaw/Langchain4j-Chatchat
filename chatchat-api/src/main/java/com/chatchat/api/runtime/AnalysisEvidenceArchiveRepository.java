package com.chatchat.api.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisEvidenceArchiveRepository extends JpaRepository<AnalysisEvidenceArchiveEntity, String> {
    Optional<AnalysisEvidenceArchiveEntity> findByArchiveIdAndTenantIdAndUserId(
        String archiveId, String tenantId, String userId);
}
