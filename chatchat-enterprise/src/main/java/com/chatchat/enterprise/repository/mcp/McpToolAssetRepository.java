package com.chatchat.enterprise.repository.mcp;

import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface McpToolAssetRepository extends JpaRepository<McpToolAsset, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tool from McpToolAsset tool where tool.id = :id")
    Optional<McpToolAsset> findLockedById(@Param("id") String id);
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
