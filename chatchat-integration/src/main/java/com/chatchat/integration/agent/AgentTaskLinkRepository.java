package com.chatchat.integration.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskLinkRepository extends JpaRepository<AgentTaskLinkEntity, String> {
    long deleteByCreatedAtEpochMsLessThan(long cutoffEpochMs);
}
