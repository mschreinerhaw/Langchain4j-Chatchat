package com.chatchat.api.mcp.repository;

import com.chatchat.api.mcp.entity.McpServiceConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServiceConfigVersionRepository extends JpaRepository<McpServiceConfigVersion, String> {

    List<McpServiceConfigVersion> findTop30ByServiceIdOrderByCreatedAtDesc(String serviceId);
}

