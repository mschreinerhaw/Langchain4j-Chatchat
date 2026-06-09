package com.chatchat.api.listener;

import com.chatchat.chat.task.AgentTaskService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
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

    private final McpToolRegistryBridge mcpToolRegistryBridge;
    private final AgentTaskService agentTaskService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("============================================");
        log.info("ChatChat Enterprise AI Application Starting");
        log.info("============================================");

        try {
            log.info("Refreshing MCP tools...");
            mcpToolRegistryBridge.refreshRegistry();
            log.info("MCP tools refreshed successfully");
            int repairedTasks = agentTaskService.reconcileLatestStateFromEvents();
            log.info("Reconciled {} Agent task snapshots from event store", repairedTasks);
            int recoveredTasks = agentTaskService.recoverActiveTasks();
            log.info("Recovered {} active Agent tasks", recoveredTasks);

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
