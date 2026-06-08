package com.chatchat.integration.mcp.repository;

import com.chatchat.integration.mcp.entity.McpServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServiceConfigRepository extends JpaRepository<McpServiceConfig, String> {

    List<McpServiceConfig> findByEnabledTrueOrderByNameAsc();
}
