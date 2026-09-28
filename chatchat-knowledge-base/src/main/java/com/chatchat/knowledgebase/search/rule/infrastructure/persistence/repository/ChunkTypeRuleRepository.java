package com.chatchat.knowledgebase.search.rule.infrastructure.persistence.repository;

import com.chatchat.knowledgebase.search.rule.infrastructure.persistence.entity.ChunkTypeRuleEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkTypeRuleRepository extends JpaRepository<ChunkTypeRuleEntity, Long> {

    List<ChunkTypeRuleEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();

    List<ChunkTypeRuleEntity> findByEnabledTrueAndVersionOrderByPriorityDescUpdatedAtDesc(Integer version);

    List<ChunkTypeRuleEntity> findByVersionOrderByPriorityDescUpdatedAtDesc(Integer version);
}
