package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.List;

/** Extension point describing the contract and evidence range of an MCP service domain. */
public interface McpDomainServiceContract {
    String contractId();
    String domainCode();
    String contractVersion();
    boolean supports(McpToolDescriptor tool);
    List<McpContractRequirement> requirements();

    /**
     * Returns requirements applicable to a concrete workflow role. Domain catalogs still expose
     * the complete requirement set through {@link #requirements()}, while invocation preflight
     * may omit executor-only requirements for discovery tools.
     */
    default List<McpContractRequirement> requirements(McpToolDescriptor tool) {
        return requirements();
    }
}
