package com.chatchat.runtime.news.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsSourceRuleRepository extends JpaRepository<NewsSourceRuleEntity, Long> {
    Optional<NewsSourceRuleEntity> findBySourceId(Long sourceId);
}
