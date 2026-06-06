package com.chatchat.mcpserver.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServiceRegistrationRepository extends JpaRepository<McpServiceRegistration, String> {

    List<McpServiceRegistration> findAllByOrderByNameAsc();

    Optional<McpServiceRegistration> findByServiceToken(String serviceToken);

    boolean existsByServiceToken(String serviceToken);
}
