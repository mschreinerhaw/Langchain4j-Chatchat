package com.chatchat.api.listener;

import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
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

    private final EnterpriseAdminService enterpriseAdminService;
    private final AgentTaskService agentTaskService;

    /**
     * Performs the on application ready operation.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("============================================");
        log.info("Agent Runtime Smart Workbench Starting");
        log.info("============================================");

        try {
            int synchronizedTools = enterpriseAdminService.syncRegisteredMcpTools().size();
            log.info("Synchronized {} MCP tools into enterprise asset authorization catalog", synchronizedTools);
            log.info("MCP Runtime OS kernel directory synchronized successfully");
        } catch (Exception e) {
            // MCP is an external runtime dependency. Keep the control plane available in a
            // degraded state so operators can repair the connection and resynchronize later.
            log.warn("MCP Runtime OS kernel directory synchronization deferred; application will start degraded", e);
        }

        try {
            int repairedTasks = agentTaskService.reconcileLatestStateFromEvents();
            log.info("Reconciled {} Agent task snapshots from event store", repairedTasks);
            int recoveredTasks = agentTaskService.recoverActiveTasks();
            log.info("Recovered {} active Agent tasks", recoveredTasks);

            log.info("============================================");
            log.info("Agent Runtime Smart Workbench started successfully");
            log.info("API Documentation: http://localhost:8080/swagger-ui.html");
            log.info("============================================");
        } catch (Exception e) {
            log.error("Critical Agent task recovery failed during application startup", e);
            throw new RuntimeException("Critical Agent task recovery failed", e);
        }
    }
}
