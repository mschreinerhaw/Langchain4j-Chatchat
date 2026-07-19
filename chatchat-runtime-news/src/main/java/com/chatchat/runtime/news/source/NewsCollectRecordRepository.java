package com.chatchat.runtime.news.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NewsCollectRecordRepository extends JpaRepository<NewsCollectRecordEntity, Long> {
    boolean existsByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByUrlHash(String urlHash);

    Optional<NewsCollectRecordEntity> findByDocumentId(String documentId);

    long countBySourceId(Long sourceId);

    Page<NewsCollectRecordEntity> findAllByOrderByIdDesc(Pageable pageable);

    Page<NewsCollectRecordEntity> findBySourceIdOrderByIdDesc(Long sourceId, Pageable pageable);

    @Query("""
        select record.id from NewsCollectRecordEntity record
        where record.collectedAt < :cutoff
        order by record.collectedAt asc, record.id asc
        """)
    List<Long> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NewsCollectRecordEntity record where record.id in :ids")
    int deleteExpiredIds(@Param("ids") List<Long> ids);
}
