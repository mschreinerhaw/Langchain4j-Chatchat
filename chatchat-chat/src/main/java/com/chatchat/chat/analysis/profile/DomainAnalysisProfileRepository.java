package com.chatchat.chat.analysis.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DomainAnalysisProfileRepository extends JpaRepository<DomainAnalysisProfileEntity, String> {
    List<DomainAnalysisProfileEntity> findByTenantIdOrderByAnalysisType(String tenantId);
}
