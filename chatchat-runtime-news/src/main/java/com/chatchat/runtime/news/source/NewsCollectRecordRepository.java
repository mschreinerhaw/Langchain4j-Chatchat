package com.chatchat.runtime.news.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsCollectRecordRepository extends JpaRepository<NewsCollectRecordEntity, Long> {
    boolean existsByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByDocumentId(String documentId);

    long countBySourceId(Long sourceId);
}
