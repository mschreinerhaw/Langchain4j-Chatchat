package com.chatchat.integration.mcp.service.center;

import com.chatchat.integration.mcp.service.center.McpCenterRecoveryService;
import com.chatchat.integration.mcp.service.center.McpCenterSyncService;
import com.chatchat.integration.mcp.service.transport.McpGatewayClient;
import com.chatchat.integration.mcp.service.config.McpServiceConfigService;
import com.chatchat.integration.mcp.service.routing.McpToolRegistryBridge;

import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpCenterRecoveryServiceTest {

    @Test
    void stopsAutomaticCenterSyncAfterFiveFailedAttempts() {
        Fixture fixture = fixture();
        when(fixture.gatewayClient.discoverTools(any(), anyInt())).thenReturn(List.of());
        when(fixture.registryBridge.listRegisteredTools()).thenReturn(List.of());
        when(fixture.centerSyncService.syncFromCenter(anyInt()))
            .thenReturn(new McpCenterSyncService.SyncResult(0, List.of(), List.of("offline")));

        for (int index = 0; index < 7; index++) {
            fixture.service.heartbeat();
        }

        verify(fixture.centerSyncService, times(5)).syncFromCenter(5000);
        assertThat(fixture.service.status())
            .extracting(
                McpCenterRecoveryService.RecoveryStatus::state,
                McpCenterRecoveryService.RecoveryStatus::attempts,
                McpCenterRecoveryService.RecoveryStatus::retryExhausted
            )
            .containsExactly("RETRY_EXHAUSTED", 5, true);
    }

    @Test
    void healthyHeartbeatDoesNotRunCenterSync() {
        Fixture fixture = fixture();
        McpToolDefinition definition = new McpToolDefinition("api_asset_query", "query", Map.of());
        when(fixture.gatewayClient.discoverTools(any(), anyInt())).thenReturn(List.of(definition));
        when(fixture.registryBridge.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool(
                "mcp_api_asset_query",
                "chatchat-mcp-server",
                "ChatChat MCP Server",
                "api_asset_query",
                "query"
            )
        ));

        fixture.service.heartbeat();

        verify(fixture.centerSyncService, never()).syncFromCenter(anyInt());
        assertThat(fixture.service.status().state()).isEqualTo("HEALTHY");
        assertThat(fixture.service.status().attempts()).isZero();
    }

    @Test
    void manualSyncRearmsRecoveryAfterRetryExhaustion() {
        Fixture fixture = fixture();
        when(fixture.gatewayClient.discoverTools(any(), anyInt())).thenReturn(List.of());
        when(fixture.registryBridge.listRegisteredTools()).thenReturn(List.of());
        when(fixture.centerSyncService.syncFromCenter(anyInt()))
            .thenReturn(new McpCenterSyncService.SyncResult(0, List.of(), List.of("offline")));
        when(fixture.centerSyncService.syncFromCenter())
            .thenReturn(new McpCenterSyncService.SyncResult(0, List.of(), List.of("offline")));
        for (int index = 0; index < 5; index++) {
            fixture.service.heartbeat();
        }

        fixture.service.syncManually();

        assertThat(fixture.service.status().retryExhausted()).isFalse();
        assertThat(fixture.service.status().attempts()).isZero();
        assertThat(fixture.service.status().state()).isEqualTo("UNHEALTHY");
    }

    private Fixture fixture() {
        McpCenterProperties properties = new McpCenterProperties();
        properties.setEnabled(true);
        properties.setAutoRecoveryEnabled(true);
        properties.setHeartbeatTimeoutMs(5000);
        properties.setMaxAutoSyncAttempts(5);

        McpServiceConfig config = new McpServiceConfig();
        config.setId("chatchat-mcp-server");
        config.setName("ChatChat MCP Server");
        config.setEnabled(true);

        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        McpGatewayClient gatewayClient = mock(McpGatewayClient.class);
        McpToolRegistryBridge registryBridge = mock(McpToolRegistryBridge.class);
        McpCenterSyncService centerSyncService = mock(McpCenterSyncService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));

        McpCenterRecoveryService service = new McpCenterRecoveryService(
            properties,
            configService,
            gatewayClient,
            registryBridge,
            centerSyncService
        );
        return new Fixture(service, gatewayClient, registryBridge, centerSyncService);
    }

    private record Fixture(
        McpCenterRecoveryService service,
        McpGatewayClient gatewayClient,
        McpToolRegistryBridge registryBridge,
        McpCenterSyncService centerSyncService
    ) {
    }
}
