package com.chatchat.chat.insight;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SemanticInsightRecipeParameterRepository
    extends JpaRepository<SemanticInsightRecipeParameterEntity, String> {
    List<SemanticInsightRecipeParameterEntity>
        findByRecipeIdInOrderByRecipeIdAscDisplayOrderAscParameterIdAsc(Collection<String> recipeIds);
}
