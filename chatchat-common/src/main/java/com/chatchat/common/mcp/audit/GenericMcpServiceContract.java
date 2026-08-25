package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.List;

/** Baseline contract automatically covering API, Python, notification, news and future MCP services. */
public final class GenericMcpServiceContract implements McpDomainServiceContract {
    @Override public String contractId() { return "runtime-os/generic-mcp-service"; }
    @Override public String domainCode() { return "generic"; }
    @Override public String contractVersion() { return "mcp_domain_contract.v1"; }
    @Override public boolean supports(McpToolDescriptor tool) { return tool != null; }

    @Override
    public List<McpContractRequirement> requirements() {
        return List.of(
            requirement(McpContractSource.INPUT_SCHEMA, "type", "MCP_INPUT_SCHEMA_MISSING", "PUBLISH_INPUT_SCHEMA"),
            requirement(McpContractSource.OUTPUT_SCHEMA, "type", "MCP_OUTPUT_SCHEMA_MISSING", "PUBLISH_OUTPUT_SCHEMA"),
            requirement(McpContractSource.GOVERNANCE, "riskLevel", "MCP_RISK_POLICY_MISSING", "REVIEW_TOOL_GOVERNANCE"),
            requirement(McpContractSource.METADATA, "contractVersion", "MCP_CONTRACT_VERSION_MISSING", "REFRESH_CONTRACT_SNAPSHOT")
        );
    }

    private McpContractRequirement requirement(McpContractSource source, String path, String code, String recovery) {
        return new McpContractRequirement(source, path, code, McpContractSeverity.ERROR,
            "Every MCP service must publish " + source.name().toLowerCase() + "." + path, recovery);
    }
}
