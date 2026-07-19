package com.chatchat.runtime.news.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import java.util.List;

import java.util.Optional;

public interface NewsAnalysisTaskRepository extends JpaRepository<NewsAnalysisTaskEntity, Long> {
    boolean existsByDocumentId(String documentId);

    Optional<NewsAnalysisTaskEntity> findByDocumentId(String documentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from NewsAnalysisTaskEntity task where task.status = :status order by task.createdAt asc, task.id asc")
    List<NewsAnalysisTaskEntity> findNextByStatus(@Param("status") NewsAnalysisStatus status, Pageable pageable);
}
