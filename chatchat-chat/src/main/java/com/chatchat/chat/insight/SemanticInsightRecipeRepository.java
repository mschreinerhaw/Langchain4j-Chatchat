package com.chatchat.chat.insight;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SemanticInsightRecipeRepository extends JpaRepository<SemanticInsightRecipeEntity, String> {
    List<SemanticInsightRecipeEntity> findByContractIdOrderByDisplayOrderAscRecipeIdAsc(String contractId);
    boolean existsByContractId(String contractId);
}
