package com.chatchat.chat.skills.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SummaryContractRepository extends JpaRepository<SummaryContractEntity, String> {

    List<SummaryContractEntity> findByContractKeyAndEnabledTrueOrderByCreatedAtDesc(String contractKey);

    boolean existsByContractKey(String contractKey);
}
