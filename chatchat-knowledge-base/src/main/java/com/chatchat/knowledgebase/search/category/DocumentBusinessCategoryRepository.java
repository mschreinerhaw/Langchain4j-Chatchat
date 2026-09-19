package com.chatchat.knowledgebase.search.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentBusinessCategoryRepository
    extends JpaRepository<DocumentBusinessCategoryEntity, String> {

    List<DocumentBusinessCategoryEntity> findAllByOrderBySortOrderAscNameAsc();

    Optional<DocumentBusinessCategoryEntity> findByNameIgnoreCase(String name);
}
