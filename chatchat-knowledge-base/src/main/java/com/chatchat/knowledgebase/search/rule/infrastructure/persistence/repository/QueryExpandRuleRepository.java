package com.chatchat.knowledgebase.search.rule.infrastructure.persistence.repository;

import com.chatchat.knowledgebase.search.rule.infrastructure.persistence.entity.QueryExpandRuleEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryExpandRuleRepository extends JpaRepository<QueryExpandRuleEntity, Long> {

    List<QueryExpandRuleEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();

    List<QueryExpandRuleEntity> findByEnabledTrueAndVersionOrderByPriorityDescUpdatedAtDesc(Integer version);

    List<QueryExpandRuleEntity> findByVersionOrderByPriorityDescUpdatedAtDesc(Integer version);
}
