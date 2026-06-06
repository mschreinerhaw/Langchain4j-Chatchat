package com.chatchat.api.listener;

import com.chatchat.api.mcp.service.McpToolRegistryBridge;
import com.chatchat.tools.builtin.BuiltInToolsBootstrap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Application startup initialization tasks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationStartupListener {

    private final BuiltInToolsBootstrap builtInToolsBootstrap;
    private final McpToolRegistryBridge mcpToolRegistryBridge;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("============================================");
        log.info("ChatChat Enterprise AI Application Starting");
        log.info("============================================");

        try {
            log.info("Initializing built-in tools...");
            builtInToolsBootstrap.initializeBuiltInTools();
            log.info("Built-in tools initialized successfully");

            log.info("Refreshing MCP tools...");
            mcpToolRegistryBridge.refreshRegistry();
            log.info("MCP tools refreshed successfully");

            log.info("============================================");
            log.info("Application started successfully");
            log.info("API Documentation: http://localhost:8080/swagger-ui.html");
            log.info("============================================");
        } catch (Exception e) {
            log.error("Error during application startup", e);
            throw new RuntimeException("Application startup failed", e);
        }
    }
}
