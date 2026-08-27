package com.chatchat.enterprise.repository.mcp;

import com.chatchat.enterprise.entity.mcp.McpToolWorkflowContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface McpToolWorkflowContractRepository extends JpaRepository<McpToolWorkflowContract, String> {

    Optional<McpToolWorkflowContract> findFirstByToolIdAndStatusOrderByContractVersionDesc(
        String toolId, String status);

    Optional<McpToolWorkflowContract> findFirstByToolIdAndContractChecksumOrderByContractVersionDesc(
        String toolId, String checksum);

    List<McpToolWorkflowContract> findByToolIdOrderByContractVersionDesc(String toolId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<McpToolWorkflowContract> findByToolIdAndStatus(String toolId, String status);
}
