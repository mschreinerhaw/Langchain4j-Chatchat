package com.chatchat.common.mcp.contract;

import java.time.Duration;
import java.util.Map;

/** Module-neutral MCP tool declaration consumed by discovery, governance and runtime publication. */
public interface McpToolContract {
    String toolName();
    String displayName();
    String description();
    String capabilityCode();
    String provider();
    String contractVersion();
    Map<String, Object> inputSchema();
    Map<String, Object> outputSchema();
    McpToolGovernance governance();
    Duration timeout();

    default void validateContract() {
        McpToolContractValidator.validate(this);
    }
}
