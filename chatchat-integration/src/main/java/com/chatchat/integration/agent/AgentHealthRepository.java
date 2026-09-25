package com.chatchat.integration.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgentHealthRepository extends JpaRepository<AgentHealthEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from AgentHealthEntity state where state.agentId = :agentId")
    Optional<AgentHealthEntity> findForUpdate(@Param("agentId") String agentId);
}
