package com.chatchat.mcpserver.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiServiceConfigRepository extends JpaRepository<ApiServiceConfig, String> {

    List<ApiServiceConfig> findByEnabledTrueOrderByToolNameAsc();

    Optional<ApiServiceConfig> findByToolNameIgnoreCase(String toolName);

    boolean existsByToolNameIgnoreCase(String toolName);
}
