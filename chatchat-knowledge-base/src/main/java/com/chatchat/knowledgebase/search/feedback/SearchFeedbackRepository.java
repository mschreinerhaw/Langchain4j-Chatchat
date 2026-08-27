package com.chatchat.knowledgebase.search.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SearchFeedbackRepository extends JpaRepository<SearchFeedbackEntity, Long> {

    List<SearchFeedbackEntity> findTop100ByQueryTextIgnoreCaseOrderByCreatedAtDesc(String queryText);
}
