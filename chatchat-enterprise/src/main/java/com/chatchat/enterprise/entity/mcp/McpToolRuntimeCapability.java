package com.chatchat.enterprise.entity.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Runtime capabilities assigned by the database to a remote MCP tool. */
@Getter
@Setter
@Entity
@Table(name = "mcp_tool_runtime_capability")
public class McpToolRuntimeCapability {

    @Id
    @Column(name = "remote_tool_name", length = 128, nullable = false)
    private String remoteToolName;

    @Column(name = "batch_execution", nullable = false)
    private boolean batchExecution;

    @Column(name = "template_execution", nullable = false)
    private boolean templateExecution;
}
