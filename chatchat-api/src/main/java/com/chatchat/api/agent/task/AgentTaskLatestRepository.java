package com.chatchat.api.agent.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskLatestRepository extends JpaRepository<AgentTaskLatestEntity, String> {

    List<AgentTaskLatestEntity> findAllByOrderByCreateTimeDesc(Pageable pageable);

    List<AgentTaskLatestEntity> findByTenantIdOrderByCreateTimeDesc(String tenantId, Pageable pageable);

    List<AgentTaskLatestEntity> findByTenantIdAndSessionIdOrderByCreateTimeDesc(String tenantId, String sessionId, Pageable pageable);
}
