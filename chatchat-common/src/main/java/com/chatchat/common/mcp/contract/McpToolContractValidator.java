package com.chatchat.common.mcp.contract;

import com.chatchat.common.tool.McpToolNamePolicy;

/** Fail-fast validator used by every MCP registration and publication path. */
public final class McpToolContractValidator {
    public static final String CONTRACT_VERSION = "mcp_tool_contract.v1";

    private McpToolContractValidator() { }

    public static void validate(McpToolContract contract) {
        if (contract == null) throw new IllegalArgumentException("MCP tool contract is required");
        McpToolNamePolicy.requirePublishableName(contract.toolName());
        required(contract.displayName(), "displayName");
        required(contract.description(), "description");
        required(contract.capabilityCode(), "capabilityCode");
        required(contract.provider(), "provider");
        if (!CONTRACT_VERSION.equals(contract.contractVersion())) {
            throw new IllegalArgumentException("Unsupported MCP tool contract version: " + contract.contractVersion());
        }
        if (contract.inputSchema() == null) throw new IllegalArgumentException("inputSchema is required");
        if (contract.outputSchema() == null) throw new IllegalArgumentException("outputSchema is required");
        if (contract.governance() == null) throw new IllegalArgumentException("governance is required");
        if (contract.timeout() == null || contract.timeout().isNegative() || contract.timeout().isZero()) {
            throw new IllegalArgumentException("positive timeout is required");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
