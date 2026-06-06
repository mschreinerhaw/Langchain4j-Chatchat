package com.chatchat.mcpserver.config;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.tool.ToolRegistryMcpAdapter;
import com.chatchat.tools.builtin.BuiltInToolsBootstrap;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfiguration {

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy());
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
        McpJsonMapper mcpJsonMapper,
        ChatChatMcpServerProperties properties
    ) {
        return HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mcpJsonMapper)
            .mcpEndpoint(normalizeEndpoint(properties.getEndpoint()))
            .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
        HttpServletStreamableServerTransportProvider transportProvider,
        ChatChatMcpServerProperties properties
    ) {
        String endpoint = normalizeEndpoint(properties.getEndpoint());
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
            new ServletRegistrationBean<>(transportProvider, endpoint);
        registration.setName("chatchatMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpSyncServer(
        HttpServletStreamableServerTransportProvider transportProvider,
        BuiltInToolsBootstrap builtInToolsBootstrap,
        ToolRegistry toolRegistry,
        ToolRegistryMcpAdapter toolRegistryMcpAdapter,
        ChatChatMcpServerProperties properties
    ) {
        builtInToolsBootstrap.initializeBuiltInTools();

        return McpServer.sync(transportProvider)
            .serverInfo(properties.getName(), properties.getVersion())
            .instructions(properties.getInstructions())
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .logging()
                .build())
            .tools(toolRegistryMcpAdapter.toToolSpecifications(toolRegistry))
            .build();
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/mcp";
        }
        String normalized = endpoint.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
