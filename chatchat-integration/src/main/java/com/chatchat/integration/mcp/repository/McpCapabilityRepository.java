package com.chatchat.integration.mcp.repository;

import com.chatchat.integration.mcp.entity.McpCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpCapabilityRepository extends JpaRepository<McpCapability, Long> {
    Optional<McpCapability> findByCapabilityCode(String capabilityCode);
}
