package com.chatchat.mcpserver.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HttpEndpointConfigRepository extends JpaRepository<HttpEndpointConfig, String> {

    List<HttpEndpointConfig> findByEnabledTrueOrderByNameAsc();

    Optional<HttpEndpointConfig> findByNameIgnoreCase(String name);

    Optional<HttpEndpointConfig> findByToolNameIgnoreCase(String toolName);
}
