package com.chatchat.chat.insight;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SemanticInsightContractRepository
    extends JpaRepository<SemanticInsightContractEntity, String> {
    List<SemanticInsightContractEntity>
        findByTenantIdAndStatusIgnoreCaseAndEnabledTrueOrderByPriorityDescUpdatedAtDesc(
            String tenantId, String status);
}
