package com.chatchat.chat.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRuntimePlanRepository extends JpaRepository<AgentRuntimePlanEntity, String> {

    Optional<AgentRuntimePlanEntity> findTopByTenantIdAndTaskIdOrderByVersionDesc(
        String tenantId, String taskId);

    List<AgentRuntimePlanEntity> findByTenantIdAndTaskIdOrderByVersionAsc(
        String tenantId, String taskId);
}
