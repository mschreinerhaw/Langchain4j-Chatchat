package com.chatchat.common.mcp.contract;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolContractTest {
    @Test
    void acceptsCompleteGovernedContract() {
        contract("mcp_tool_contract.v1", McpToolGovernance.readOnly()).validateContract();
    }

    @Test
    void rejectsProtocolAndGovernanceDrift() {
        assertThatThrownBy(() -> contract("mcp_tool_contract.v2", McpToolGovernance.readOnly()).validateContract())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported MCP tool contract version");
        assertThatThrownBy(() -> contract("mcp_tool_contract.v1", null).validateContract())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("governance is required");
    }

    private McpToolContract contract(String version, McpToolGovernance governance) {
        return new McpToolContract() {
            public String toolName() { return "docker_query"; }
            public String displayName() { return "Docker query"; }
            public String description() { return "Read Docker state"; }
            public String capabilityCode() { return "ops"; }
            public String provider() { return "test"; }
            public String contractVersion() { return version; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public Map<String, Object> outputSchema() { return Map.of("type", "object"); }
            public McpToolGovernance governance() { return governance; }
            public Duration timeout() { return Duration.ofSeconds(10); }
        };
    }
}
