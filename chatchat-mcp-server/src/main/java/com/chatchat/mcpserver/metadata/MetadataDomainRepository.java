package com.chatchat.mcpserver.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetadataDomainRepository extends JpaRepository<MetadataDomain, String> {
    Optional<MetadataDomain> findByCodeIgnoreCase(String code);
    List<MetadataDomain> findAllByOrderByPriorityAscNameAsc();
    List<MetadataDomain> findByEnabledTrueOrderByPriorityAscNameAsc();
}
