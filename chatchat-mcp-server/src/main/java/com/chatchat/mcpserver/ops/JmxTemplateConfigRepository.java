package com.chatchat.mcpserver.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JmxTemplateConfigRepository extends JpaRepository<JmxTemplateConfig, String> {
    Optional<JmxTemplateConfig> findByCode(String code);
    List<JmxTemplateConfig> findByEnabledTrueOrderByCodeAsc();
}
