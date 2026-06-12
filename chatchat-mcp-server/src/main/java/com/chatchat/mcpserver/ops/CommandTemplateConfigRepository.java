package com.chatchat.mcpserver.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommandTemplateConfigRepository extends JpaRepository<CommandTemplateConfig, String> {

    Optional<CommandTemplateConfig> findByCode(String code);

    List<CommandTemplateConfig> findByEnabledTrueOrderByCodeAsc();
}
