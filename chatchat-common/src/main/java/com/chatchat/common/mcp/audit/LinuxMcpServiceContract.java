package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;

import java.util.List;

/** Standard contract for Linux, SSH and template-governed Docker diagnostics. */
public final class LinuxMcpServiceContract extends AbstractMcpDomainServiceContract {
    public LinuxMcpServiceContract() { super("linux", "ssh", "docker", "linux_command"); }
    @Override public String contractId() { return "runtime-os/linux-mcp-service"; }
    @Override public String domainCode() { return "linux"; }

    @Override
    public List<McpContractRequirement> requirements() {
        return List.of(
            required(McpContractSource.INPUT_SCHEMA, "properties.template", "LINUX_TEMPLATE_ARGUMENT_MISSING",
                "Linux execution must be bound to a discovered template", "REDISCOVER_TEMPLATE"),
            required(McpContractSource.OUTPUT_SCHEMA, "type", "LINUX_OUTPUT_SCHEMA_MISSING",
                "Linux execution must publish its stdout/stderr result envelope", "PUBLISH_OUTPUT_SCHEMA"),
            required(McpContractSource.GOVERNANCE, "operationType", "LINUX_OPERATION_POLICY_MISSING",
                "Linux execution must declare read/write operation governance", "REVIEW_LINUX_GOVERNANCE"),
            required(McpContractSource.METADATA, "contractVersion", "LINUX_CONTRACT_VERSION_MISSING",
                "Linux discovery evidence must carry a contract version", "REFRESH_CONTRACT_SNAPSHOT")
        );
    }

    @Override
    public List<McpContractRequirement> requirements(McpToolDescriptor tool) {
        ToolWorkflowRole role = ToolWorkflowContract.resolveDescriptorRole(
            tool == null ? null : tool.localToolName(), tool == null ? null : tool.metadata());
        if (role != ToolWorkflowRole.TEMPLATE_DISCOVERY && role != ToolWorkflowRole.ASSET_DISCOVERY) {
            return requirements();
        }
        return requirements().stream()
            .filter(requirement -> !"LINUX_TEMPLATE_ARGUMENT_MISSING".equals(requirement.code()))
            .toList();
    }
}
