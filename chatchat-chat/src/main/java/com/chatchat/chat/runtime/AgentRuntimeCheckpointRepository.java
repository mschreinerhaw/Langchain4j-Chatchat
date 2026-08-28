package com.chatchat.chat.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRuntimeCheckpointRepository extends JpaRepository<AgentRuntimeCheckpointEntity, String> {

    List<AgentRuntimeCheckpointEntity> findByRunIdOrderByStepIdAsc(String runId);

    void deleteByRunId(String runId);
}
