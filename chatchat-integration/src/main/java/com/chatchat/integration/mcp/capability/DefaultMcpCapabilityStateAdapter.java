package com.chatchat.integration.mcp.capability;

import com.chatchat.integration.mcp.service.McpCapabilityService;
import com.chatchat.runtime.mcp.registry.McpCapabilityStatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Persistence-backed adapter for the Runtime MCP capability-state port. */
@Component
@RequiredArgsConstructor
public class DefaultMcpCapabilityStateAdapter implements McpCapabilityStatePort {
    private final McpCapabilityService capabilities;

    @Override
    public boolean enabled(String capabilityCode) {
        return capabilities.findByCode(capabilityCode)
            .map(capability -> capability.isEnabled())
            .orElse(true);
    }
}
