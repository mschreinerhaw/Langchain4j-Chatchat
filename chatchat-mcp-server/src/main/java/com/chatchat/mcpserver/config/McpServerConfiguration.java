package com.chatchat.mcpserver.config;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.tool.McpServerToolRegistrar;
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

import java.util.List;

@Configuration
public class McpServerConfiguration {

    /**
     * Performs the mcp json mapper operation.
     *
     * @param objectMapper the object mapper value
     * @return the operation result
     */
    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy());
    }

    /**
     * Performs the mcp transport provider operation.
     *
     * @param mcpJsonMapper the mcp json mapper value
     * @param properties the properties value
     * @return the operation result
     */
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

    /**
     * Performs the mcp servlet operation.
     *
     * @param transportProvider the transport provider value
     * @param properties the properties value
     * @return the operation result
     */
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

    /**
     * Performs the mcp sync server operation.
     *
     * @param transportProvider the transport provider value
     * @param builtInToolsBootstrap the built in tools bootstrap value
     * @param toolRegistry the tool registry value
     * @param toolRegistryMcpAdapter the tool registry mcp adapter value
     * @param properties the properties value
     * @param toolRegistrars the tool registrars value
     * @return the operation result
     */
    @Bean(destroyMethod = "close")
    public McpSyncServer mcpSyncServer(
        HttpServletStreamableServerTransportProvider transportProvider,
        BuiltInToolsBootstrap builtInToolsBootstrap,
        ToolRegistry toolRegistry,
        ToolRegistryMcpAdapter toolRegistryMcpAdapter,
        ChatChatMcpServerProperties properties,
        List<McpServerToolRegistrar> toolRegistrars
    ) {
        builtInToolsBootstrap.initializeBuiltInTools();
        if (toolRegistrars != null) {
            toolRegistrars.forEach(registrar -> registrar.registerTools(toolRegistry));
        }

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

    /**
     * Normalizes the endpoint.
     *
     * @param endpoint the endpoint value
     * @return the operation result
     */
    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/mcp";
        }
        String normalized = endpoint.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
