package com.chatchat.mcpserver.datacapability.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CapabilityExecutionRepository extends JpaRepository<CapabilityExecution, String> {
    List<CapabilityExecution> findTop50ByCapabilityCodeOrderByStartedAtDesc(String code);
}
