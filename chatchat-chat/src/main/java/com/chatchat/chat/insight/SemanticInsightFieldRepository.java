package com.chatchat.chat.insight;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SemanticInsightFieldRepository extends JpaRepository<SemanticInsightFieldEntity, String> {
    List<SemanticInsightFieldEntity> findByContractIdOrderByDisplayOrderAscFieldIdAsc(String contractId);
    boolean existsByContractId(String contractId);
}
