package com.chatchat.mcpserver.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MetadataTermMappingRepository extends JpaRepository<MetadataTermMapping, String> {
    Optional<MetadataTermMapping> findByScenarioIdAndNormalizedTerm(String scenarioId, String normalizedTerm);
    List<MetadataTermMapping> findAllByOrderByPriorityAscTermAsc();
    List<MetadataTermMapping> findByScenarioIdOrderByPriorityAscTermAsc(String scenarioId);
    List<MetadataTermMapping> findByScenarioIdInAndEnabledTrueOrderByPriorityAscTermAsc(Collection<String> scenarioIds);
    long countByScenarioId(String scenarioId);
    void deleteByScenarioId(String scenarioId);
}
