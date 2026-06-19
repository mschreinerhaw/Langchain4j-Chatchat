package com.chatchat.knowledgebase.search.rule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SemanticLexiconEntryRepository extends JpaRepository<SemanticLexiconEntryEntity, Long> {

    boolean existsByBuiltinTrue();

    Optional<SemanticLexiconEntryEntity> findFirstByNormalizedTermAndBuiltinTrue(String normalizedTerm);

    List<SemanticLexiconEntryEntity> findByEnabledTrueOrderByBuiltinAscPriorityDescUpdatedAtDesc();

    List<SemanticLexiconEntryEntity> findByEnabledTrueAndVersionOrderByBuiltinAscPriorityDescUpdatedAtDesc(Integer version);

    List<SemanticLexiconEntryEntity> findByVersionOrderByBuiltinAscPriorityDescUpdatedAtDesc(Integer version);
}
