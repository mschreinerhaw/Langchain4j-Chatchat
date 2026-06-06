package com.chatchat.api.mcp.repository;

import com.chatchat.api.mcp.entity.McpServiceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServiceConfigRepository extends JpaRepository<McpServiceConfig, String> {

    List<McpServiceConfig> findByEnabledTrueOrderByNameAsc();
}
