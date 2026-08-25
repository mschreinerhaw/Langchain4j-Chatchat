package com.chatchat.common.mcp.audit;

import java.util.List;

/** Serializable public view of one injected MCP domain contract. */
public record McpDomainContractDescriptor(
    String contractId,
    String domainCode,
    String contractVersion,
    List<McpContractRequirement> requirements
) {
    public McpDomainContractDescriptor {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    public static McpDomainContractDescriptor from(McpDomainServiceContract contract) {
        return new McpDomainContractDescriptor(contract.contractId(), contract.domainCode(),
            contract.contractVersion(), contract.requirements());
    }
}
