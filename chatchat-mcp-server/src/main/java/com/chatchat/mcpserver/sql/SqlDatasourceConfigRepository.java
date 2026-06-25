package com.chatchat.mcpserver.sql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SqlDatasourceConfigRepository extends JpaRepository<SqlDatasourceConfig, String> {

    List<SqlDatasourceConfig> findByEnabledTrueOrderByNameAsc();

    Optional<SqlDatasourceConfig> findByNameIgnoreCase(String name);

    Optional<SqlDatasourceConfig> findByToolNameIgnoreCase(String toolName);
}
