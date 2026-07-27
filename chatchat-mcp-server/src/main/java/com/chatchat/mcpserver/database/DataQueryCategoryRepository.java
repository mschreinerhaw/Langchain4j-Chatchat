package com.chatchat.mcpserver.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataQueryCategoryRepository extends JpaRepository<DataQueryCategory, String> {
    List<DataQueryCategory> findAllByOrderBySortOrderAscNameAsc();
    List<DataQueryCategory> findByEnabledTrueOrderBySortOrderAscNameAsc();
    Optional<DataQueryCategory> findByCodeIgnoreCase(String code);
}
