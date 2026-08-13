package com.chatchat.chat.dag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DagGovernanceContractRepository
    extends JpaRepository<DagGovernanceContractEntity, String> {

    List<DagGovernanceContractEntity> findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(String contractKey);

    boolean existsByContractKey(String contractKey);
}
