package com.chatchat.mcpserver.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetadataScenarioRepository extends JpaRepository<MetadataScenario, String> {
    Optional<MetadataScenario> findByCodeIgnoreCase(String code);
    List<MetadataScenario> findAllByOrderByPriorityAscNameAsc();
    List<MetadataScenario> findByEnabledTrueOrderByPriorityAscNameAsc();
    Optional<MetadataScenario> findFirstByFallbackScenarioTrue();
    long countByDomainId(String domainId);
}
