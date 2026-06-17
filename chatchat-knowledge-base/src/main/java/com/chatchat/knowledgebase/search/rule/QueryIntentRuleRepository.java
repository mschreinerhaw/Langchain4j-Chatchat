package com.chatchat.knowledgebase.search.rule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryIntentRuleRepository extends JpaRepository<QueryIntentRuleEntity, Long> {

    List<QueryIntentRuleEntity> findByEnabledTrueOrderByPriorityDescUpdatedAtDesc();

    List<QueryIntentRuleEntity> findByEnabledTrueAndVersionOrderByPriorityDescUpdatedAtDesc(Integer version);

    List<QueryIntentRuleEntity> findByVersionOrderByPriorityDescUpdatedAtDesc(Integer version);
}
