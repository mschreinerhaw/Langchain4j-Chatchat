package com.chatchat.runtime.news.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewsSourceRepository extends JpaRepository<NewsSourceEntity, Long> {
    Optional<NewsSourceEntity> findBySourceCode(String sourceCode);

    List<NewsSourceEntity> findByEnabledTrue();

    List<NewsSourceEntity> findByCapabilityIdOrderByUpdatedAtDesc(Long capabilityId);
}
