package com.chatchat.knowledgebase.search.rule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryExpandRuleRepository extends JpaRepository<QueryExpandRuleEntity, Long> {

    List<QueryExpandRuleEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();

    List<QueryExpandRuleEntity> findByEnabledTrueAndVersionOrderByPriorityDescUpdatedAtDesc(Integer version);

    List<QueryExpandRuleEntity> findByVersionOrderByPriorityDescUpdatedAtDesc(Integer version);
}
