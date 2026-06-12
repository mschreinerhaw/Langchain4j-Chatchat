package com.chatchat.chat.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentExperienceRepository extends JpaRepository<AgentExperienceEntity, String> {

    Optional<AgentExperienceEntity> findByTenantIdAndTaskId(String tenantId, String taskId);

    List<AgentExperienceEntity> findByTenantIdOrderByUpdateTimeDesc(String tenantId, Pageable pageable);

    List<AgentExperienceEntity> findByTenantIdOrderByFeedbackScoreDesc(String tenantId, Pageable pageable);

    List<AgentExperienceEntity> findByTenantId(String tenantId);

    @Query("""
        select e.scenarioKey as scenarioKey,
               e.scenarioName as scenarioName,
               count(e) as total,
               avg(e.feedbackScore) as averageScore
        from AgentExperienceEntity e
        where e.tenantId = :tenantId
        group by e.scenarioKey, e.scenarioName
        order by avg(e.feedbackScore) desc, count(e) desc
        """)
    List<ScenarioMetric> summarizeScenarios(@Param("tenantId") String tenantId, Pageable pageable);

    @Query("select count(e) from AgentExperienceEntity e where e.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") String tenantId);

    interface ScenarioMetric {

        String getScenarioKey();

        String getScenarioName();

        long getTotal();

        Double getAverageScore();
    }
}
