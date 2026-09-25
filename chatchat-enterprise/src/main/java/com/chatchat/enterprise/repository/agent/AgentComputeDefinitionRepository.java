package com.chatchat.enterprise.repository.agent;

import com.chatchat.enterprise.entity.agent.AgentComputeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentComputeDefinitionRepository extends JpaRepository<AgentComputeDefinition, String> {
    Optional<AgentComputeDefinition> findByAgentId(String agentId);
    void deleteByAgentId(String agentId);
}
