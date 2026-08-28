package com.chatchat.chat.task.learning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentOptimizationProposalRepository
    extends JpaRepository<AgentOptimizationProposalEntity, String> {

    List<AgentOptimizationProposalEntity> findByTenantIdAndAgentIdOrderByCreatedAtDesc(
        String tenantId, String agentId);
}
