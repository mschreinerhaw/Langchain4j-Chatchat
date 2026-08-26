package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.runtime.mcp.kernel.DefaultMcpRuntimeKernel;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.integration.mcp.grpc.GrpcBackedMcpRuntimeKernel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the Runtime-owned MCP Kernel with integration-owned provider adapters. */
@Configuration
public class McpRuntimeKernelConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "chatchat.mcp.grpc.client", name = "enabled", havingValue = "false")
    McpRuntimeKernel mcpRuntimeKernel(DynamicMcpServiceDirectory directory,
                                      DynamicMcpRuntimeContractService contracts) {
        return new DefaultMcpRuntimeKernel(directory, contracts);
    }

    @Bean
    @ConditionalOnProperty(prefix = "chatchat.mcp.grpc.client", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    McpRuntimeKernel grpcBackedMcpRuntimeKernel(
        @Qualifier("mcpRuntimeTransportPort") McpRuntimeTransportPort transport
    ) {
        return new GrpcBackedMcpRuntimeKernel(transport);
    }
}
