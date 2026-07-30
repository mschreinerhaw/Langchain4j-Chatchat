package com.chatchat.mcpserver.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessCategoryRepository extends JpaRepository<BusinessCategory, String> {
    Optional<BusinessCategory> findByCodeIgnoreCase(String code);
    List<BusinessCategory> findAllByOrderBySortOrderAscNameAsc();
    List<BusinessCategory> findByEnabledTrueOrderBySortOrderAscNameAsc();
}
