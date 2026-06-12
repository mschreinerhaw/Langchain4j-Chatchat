package com.chatchat.mcpserver.sql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SqlTemplateConfigRepository extends JpaRepository<SqlTemplateConfig, String> {

    Optional<SqlTemplateConfig> findByCode(String code);

    List<SqlTemplateConfig> findByEnabledTrueOrderByCodeAsc();
}
