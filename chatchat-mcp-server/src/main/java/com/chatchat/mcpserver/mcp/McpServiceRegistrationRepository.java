package com.chatchat.mcpserver.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServiceRegistrationRepository extends JpaRepository<McpServiceRegistration, String> {

    /**
     * Finds the all by order by name asc.
     *
     * @return the matching all by order by name asc
     */
    List<McpServiceRegistration> findAllByOrderByNameAsc();

    /**
     * Finds the by service token.
     *
     * @param serviceToken the service token value
     * @return the matching by service token
     */
    Optional<McpServiceRegistration> findByServiceToken(String serviceToken);

    /**
     * Returns whether exists by service token.
     *
     * @param serviceToken the service token value
     * @return whether the condition is satisfied
     */
    boolean existsByServiceToken(String serviceToken);
}
