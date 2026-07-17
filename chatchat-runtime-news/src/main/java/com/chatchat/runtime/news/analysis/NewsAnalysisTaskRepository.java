package com.chatchat.runtime.news.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsAnalysisTaskRepository extends JpaRepository<NewsAnalysisTaskEntity, Long> {
    boolean existsByDocumentId(String documentId);

    Optional<NewsAnalysisTaskEntity> findByDocumentId(String documentId);
}
