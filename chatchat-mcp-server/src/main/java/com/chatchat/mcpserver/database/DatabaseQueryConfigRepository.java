package com.chatchat.mcpserver.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatabaseQueryConfigRepository extends JpaRepository<DatabaseQueryConfig, String> {

    List<DatabaseQueryConfig> findAllByOrderByToolNameAsc();

    List<DatabaseQueryConfig> findByEnabledTrueOrderByToolNameAsc();

    Optional<DatabaseQueryConfig> findByToolNameIgnoreCase(String toolName);
}
