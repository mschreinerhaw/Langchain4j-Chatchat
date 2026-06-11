package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.McpToolAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpToolAssetRepository extends JpaRepository<McpToolAsset, String> {
    /**
     * Finds the by local tool name.
     *
     * @param localToolName the local tool name value
     * @return the matching by local tool name
     */
    Optional<McpToolAsset> findByLocalToolName(String localToolName);

    /**
     * Finds the by service id order by local tool name asc.
     *
     * @param serviceId the service id value
     * @return the matching by service id order by local tool name asc
     */
    List<McpToolAsset> findByServiceIdOrderByLocalToolNameAsc(String serviceId);

    /**
     * Finds the all by order by local tool name asc.
     *
     * @return the matching all by order by local tool name asc
     */
    List<McpToolAsset> findAllByOrderByLocalToolNameAsc();
}
