package com.chatchat.mcpserver.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiServiceConfigRepository extends JpaRepository<ApiServiceConfig, String> {

    /**
     * Finds the by enabled true order by tool name asc.
     *
     * @return the matching by enabled true order by tool name asc
     */
    List<ApiServiceConfig> findByEnabledTrueOrderByToolNameAsc();

    /**
     * Finds the by tool name ignore case.
     *
     * @param toolName the tool name value
     * @return the matching by tool name ignore case
     */
    Optional<ApiServiceConfig> findByToolNameIgnoreCase(String toolName);

    /**
     * Returns whether exists by tool name ignore case.
     *
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    boolean existsByToolNameIgnoreCase(String toolName);
}
