package com.chatchat.integration.mcp.grpc;

import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit local fallback for embedded deployments; distributed API deployments use gRPC. */
@Configuration
public class McpRuntimeTransportConfiguration {

    @Bean("mcpRuntimeTransportPort")
    @ConditionalOnProperty(prefix = "chatchat.mcp.grpc.client", name = "enabled", havingValue = "false")
    McpRuntimeTransportPort localMcpRuntimeTransportPort(McpRuntimeKernel kernel) {
        return kernel;
    }
}
