package com.chatchat.mcpserver.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatabaseQueryConfigRepository extends JpaRepository<DatabaseQueryConfig, String> {

    /**
     * Finds the all by order by tool name asc.
     *
     * @return the matching all by order by tool name asc
     */
    List<DatabaseQueryConfig> findAllByOrderByToolNameAsc();

    /**
     * Finds the by enabled true order by tool name asc.
     *
     * @return the matching by enabled true order by tool name asc
     */
    List<DatabaseQueryConfig> findByEnabledTrueOrderByToolNameAsc();

    /**
     * Finds the by tool name ignore case.
     *
     * @param toolName the tool name value
     * @return the matching by tool name ignore case
     */
    Optional<DatabaseQueryConfig> findByToolNameIgnoreCase(String toolName);
}
