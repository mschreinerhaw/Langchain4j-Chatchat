package com.chatchat.integration.mcp.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpToolRegistryBridgeLifecycleTest {

    @Test
    void initialDiscoveryRunsOnlyAfterApplicationIsReady() throws Exception {
        McpServiceConfigService configService = mock(McpServiceConfigService.class);

        new McpToolRegistryBridge(
            mock(ToolRegistry.class),
            configService,
            mock(McpGatewayClient.class),
            new ObjectMapper()
        );

        verifyNoInteractions(configService);

        Method initialize = McpToolRegistryBridge.class.getMethod("initialize");
        EventListener eventListener = initialize.getAnnotation(EventListener.class);
        Order order = initialize.getAnnotation(Order.class);

        assertThat(eventListener).isNotNull();
        assertThat(eventListener.value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void readyCallbackRefreshesEnabledServices() {
        McpServiceConfigService configService = mock(McpServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of());
        McpToolRegistryBridge bridge = new McpToolRegistryBridge(
            mock(ToolRegistry.class),
            configService,
            mock(McpGatewayClient.class),
            new ObjectMapper()
        );

        bridge.initialize();

        org.mockito.Mockito.verify(configService).listEnabled();
    }
}
