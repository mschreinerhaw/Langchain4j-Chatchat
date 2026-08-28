package com.chatchat.chat.skills.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentReleaseRepository extends JpaRepository<AgentReleaseEntity, String> {

    Optional<AgentReleaseEntity> findTopByAgentIdOrderByReleaseVersionDesc(String agentId);

    Optional<AgentReleaseEntity> findTopByAgentIdAndStatusOrderByReleaseVersionDesc(String agentId, String status);

    List<AgentReleaseEntity> findByAgentIdOrderByReleaseVersionDesc(String agentId);
}
