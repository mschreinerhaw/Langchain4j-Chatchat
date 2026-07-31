package com.chatchat.integration.mcp.service;

import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Monitors enabled MCP services and replays the center synchronization workflow
 * when a service is reachable but not registered, or when it becomes unreachable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpCenterRecoveryService {

    private final McpCenterProperties properties;
    private final McpServiceConfigService configService;
    private final McpGatewayClient gatewayClient;
    private final McpToolRegistryBridge registryBridge;
    private final McpCenterSyncService centerSyncService;

    private int autoSyncAttempts;
    private boolean retryExhausted;
    private String state = "UNKNOWN";
    private String lastFailure = "";
    private long lastHeartbeatAt;
    private long lastSuccessAt;
    private long lastAutoSyncAt;

    @Scheduled(
        initialDelayString = "${chatchat.mcp.center.heartbeat-initial-delay-ms:10000}",
        fixedDelayString = "${chatchat.mcp.center.heartbeat-interval-ms:15000}"
    )
    public synchronized void heartbeat() {
        if (!properties.isEnabled() || !properties.isAutoRecoveryEnabled()) {
            state = "DISABLED";
            return;
        }

        HealthCheck health = inspectHealth();
        lastHeartbeatAt = System.currentTimeMillis();
        if (health.healthy()) {
            markHealthy();
            return;
        }

        state = retryExhausted ? "RETRY_EXHAUSTED" : "UNHEALTHY";
        lastFailure = health.message();
        if (retryExhausted) {
            log.debug("MCP auto recovery remains stopped after {} failed attempts: {}",
                autoSyncAttempts, lastFailure);
            return;
        }

        int maxAttempts = Math.max(1, properties.getMaxAutoSyncAttempts());
        autoSyncAttempts++;
        lastAutoSyncAt = System.currentTimeMillis();
        log.warn("MCP heartbeat unhealthy; running center auto-sync attempt {}/{}: {}",
            autoSyncAttempts, maxAttempts, lastFailure);
        try {
            centerSyncService.syncFromCenter(heartbeatTimeoutMs());
            HealthCheck recovered = inspectHealth();
            lastHeartbeatAt = System.currentTimeMillis();
            if (recovered.healthy()) {
                log.info("MCP center auto-sync recovered the registry on attempt {}/{}",
                    autoSyncAttempts, maxAttempts);
                markHealthy();
                return;
            }
            lastFailure = recovered.message();
        } catch (Exception ex) {
            lastFailure = firstText(ex.getMessage(), ex.getClass().getSimpleName());
            log.warn("MCP center auto-sync attempt {}/{} failed: {}",
                autoSyncAttempts, maxAttempts, lastFailure);
        }

        if (autoSyncAttempts >= maxAttempts) {
            retryExhausted = true;
            state = "RETRY_EXHAUSTED";
            log.error("MCP center auto recovery stopped after {} failed attempts. "
                + "A manual center sync or application restart is required to re-arm it. Last error: {}",
                autoSyncAttempts, lastFailure);
        } else {
            state = "RECOVERING";
        }
    }

    /**
     * Executes the same synchronization workflow as the UI button and re-arms
     * automatic recovery even if the previous five attempts were exhausted.
     */
    public synchronized McpCenterSyncService.SyncResult syncManually() {
        autoSyncAttempts = 0;
        retryExhausted = false;
        state = "MANUAL_SYNC";
        try {
            McpCenterSyncService.SyncResult result = centerSyncService.syncFromCenter();
            HealthCheck health = inspectHealth();
            lastHeartbeatAt = System.currentTimeMillis();
            if (health.healthy()) {
                markHealthy();
            } else {
                state = "UNHEALTHY";
                lastFailure = health.message();
            }
            return result;
        } catch (RuntimeException ex) {
            state = "UNHEALTHY";
            lastFailure = firstText(ex.getMessage(), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    public synchronized RecoveryStatus status() {
        return new RecoveryStatus(
            properties.isEnabled() && properties.isAutoRecoveryEnabled(),
            state,
            autoSyncAttempts,
            Math.max(1, properties.getMaxAutoSyncAttempts()),
            retryExhausted,
            lastFailure,
            lastHeartbeatAt,
            lastSuccessAt,
            lastAutoSyncAt
        );
    }

    HealthCheck inspectHealth() {
        List<McpServiceConfig> services;
        try {
            services = configService.listEnabled();
        } catch (Exception ex) {
            return HealthCheck.failure("failed to load enabled MCP services: "
                + firstText(ex.getMessage(), ex.getClass().getSimpleName()));
        }
        if (services.isEmpty()) {
            return HealthCheck.failure("no enabled MCP service is registered");
        }

        Set<String> registered = new LinkedHashSet<>();
        for (McpToolRegistryBridge.RegisteredMcpTool tool : registryBridge.listRegisteredTools()) {
            registered.add(tool.serviceId() + "\n" + tool.remoteToolName());
        }

        List<String> failures = new ArrayList<>();
        for (McpServiceConfig service : services) {
            List<McpToolDefinition> discovered;
            try {
                discovered = gatewayClient.discoverTools(service, heartbeatTimeoutMs());
            } catch (Exception ex) {
                failures.add(service.getName() + ": "
                    + firstText(ex.getMessage(), ex.getClass().getSimpleName()));
                continue;
            }
            if (discovered == null || discovered.isEmpty()) {
                failures.add(service.getName() + ": heartbeat returned no tools");
                continue;
            }
            Set<String> expected = new LinkedHashSet<>();
            for (McpToolDefinition tool : discovered) {
                expected.add(service.getId() + "\n" + tool.name());
            }
            Set<String> actual = new LinkedHashSet<>();
            String prefix = service.getId() + "\n";
            registered.stream().filter(value -> value.startsWith(prefix)).forEach(actual::add);
            if (!actual.equals(expected)) {
                failures.add(service.getName() + ": discovered " + expected.size()
                    + " tools but runtime registry has " + actual.size());
            }
        }
        return failures.isEmpty()
            ? HealthCheck.success()
            : HealthCheck.failure(String.join("; ", failures));
    }

    private void markHealthy() {
        state = "HEALTHY";
        autoSyncAttempts = 0;
        retryExhausted = false;
        lastFailure = "";
        lastSuccessAt = System.currentTimeMillis();
    }

    private int heartbeatTimeoutMs() {
        return Math.max(1000, properties.getHeartbeatTimeoutMs());
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    record HealthCheck(boolean healthy, String message) {
        static HealthCheck success() {
            return new HealthCheck(true, "");
        }

        static HealthCheck failure(String message) {
            return new HealthCheck(false, message == null ? "MCP heartbeat failed" : message);
        }
    }

    public record RecoveryStatus(
        boolean enabled,
        String state,
        int attempts,
        int maxAttempts,
        boolean retryExhausted,
        String lastFailure,
        long lastHeartbeatAt,
        long lastSuccessAt,
        long lastAutoSyncAt
    ) {
    }
}
