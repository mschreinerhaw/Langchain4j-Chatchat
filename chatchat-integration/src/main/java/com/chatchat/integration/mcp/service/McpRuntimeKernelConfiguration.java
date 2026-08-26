package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.runtime.mcp.kernel.DefaultMcpRuntimeKernel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the Runtime-owned MCP Kernel with integration-owned provider adapters. */
@Configuration
public class McpRuntimeKernelConfiguration {

    @Bean
    McpRuntimeKernel mcpRuntimeKernel(DynamicMcpServiceDirectory directory,
                                      DynamicMcpRuntimeContractService contracts) {
        return new DefaultMcpRuntimeKernel(directory, contracts);
    }
}
