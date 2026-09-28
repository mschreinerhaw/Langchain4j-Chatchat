package com.chatchat.knowledgebase.search.rule.infrastructure.persistence.repository;

import com.chatchat.knowledgebase.search.rule.infrastructure.persistence.entity.RuleVersionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleVersionRepository extends JpaRepository<RuleVersionEntity, Long> {

    Optional<RuleVersionEntity> findFirstByTypeAndActiveTrueOrderByVersionDesc(String type);

    Optional<RuleVersionEntity> findFirstByTypeAndVersion(String type, Integer version);

    List<RuleVersionEntity> findByTypeOrderByVersionDesc(String type);

    List<RuleVersionEntity> findAllByOrderByTypeAscVersionDesc();
}
