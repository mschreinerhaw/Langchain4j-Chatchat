package com.chatchat.mcpserver.external;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExternalMcpServiceRepository extends JpaRepository<ExternalMcpService, String> {
    List<ExternalMcpService> findAllByOrderByNameAsc();
}
