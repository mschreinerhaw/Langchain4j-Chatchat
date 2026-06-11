package com.chatchat.integration.mcp.repository;

import com.chatchat.integration.mcp.entity.McpServiceConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServiceConfigVersionRepository extends JpaRepository<McpServiceConfigVersion, String> {

    /**
     * Finds the top30 by service id order by created at desc.
     *
     * @param serviceId the service id value
     * @return the matching top30 by service id order by created at desc
     */
    List<McpServiceConfigVersion> findTop30ByServiceIdOrderByCreatedAtDesc(String serviceId);
}

