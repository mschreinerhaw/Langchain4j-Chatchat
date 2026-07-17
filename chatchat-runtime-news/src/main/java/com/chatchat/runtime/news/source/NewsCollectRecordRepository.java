package com.chatchat.runtime.news.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface NewsCollectRecordRepository extends JpaRepository<NewsCollectRecordEntity, Long> {
    boolean existsByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByDocumentId(String documentId);

    long countBySourceId(Long sourceId);

    Page<NewsCollectRecordEntity> findAllByOrderByIdDesc(Pageable pageable);

    Page<NewsCollectRecordEntity> findBySourceIdOrderByIdDesc(Long sourceId, Pageable pageable);
}
