package com.chatchat.integration.mcp.admin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.admin.McpAdministrationPort;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.service.McpCenterRecoveryService;
import com.chatchat.integration.mcp.service.McpCenterSyncService;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpStdioProxyService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMcpAdministrationAdapterTest {

    @Test
    void createOwnsPersistenceSessionInvalidationAndRuntimeRefreshTransaction() {
        McpServiceConfigService configs = mock(McpServiceConfigService.class);
        McpStdioProxyService stdio = mock(McpStdioProxyService.class);
        McpRuntimeKernel kernel = mock(McpRuntimeKernel.class);
        McpServiceConfig saved = service("svc-1");
        when(configs.create(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        DefaultMcpAdministrationAdapter adapter = adapter(configs, stdio, kernel);
        McpAdministrationPort.ServiceConfiguration result = adapter.createService(draft());

        assertThat(result.id()).isEqualTo("svc-1");
        InOrder order = inOrder(configs, stdio, kernel);
        order.verify(configs).create(org.mockito.ArgumentMatchers.any());
        order.verify(stdio).closeSession("svc-1");
        order.verify(kernel).refresh();
    }

    @Test
    void disablingServiceClosesSessionBeforeRefreshingRuntime() {
        McpServiceConfigService configs = mock(McpServiceConfigService.class);
        McpStdioProxyService stdio = mock(McpStdioProxyService.class);
        McpRuntimeKernel kernel = mock(McpRuntimeKernel.class);
        when(configs.setEnabled("svc-1", false)).thenReturn(service("svc-1"));

        adapter(configs, stdio, kernel).setServiceEnabled("svc-1", false);

        InOrder order = inOrder(configs, stdio, kernel);
        order.verify(configs).setEnabled("svc-1", false);
        order.verify(stdio).closeSession("svc-1");
        order.verify(kernel).refresh();
    }

    private DefaultMcpAdministrationAdapter adapter(McpServiceConfigService configs,
                                                     McpStdioProxyService stdio,
                                                     McpRuntimeKernel kernel) {
        return new DefaultMcpAdministrationAdapter(configs, stdio, mock(McpToolRegistryBridge.class), kernel,
            mock(McpCenterSyncService.class), mock(McpCenterRecoveryService.class), mock(ToolRegistry.class),
            new ObjectMapper());
    }

    private McpAdministrationPort.ServiceConfigurationDraft draft() {
        return new McpAdministrationPort.ServiceConfigurationDraft("service", "http://localhost", "/tools",
            "/tools/call", "legacy_http", null, null, null, null, null, 1000, true, true, Map.of(),
            false, "http", null, null, null, null);
    }

    private McpServiceConfig service(String id) {
        McpServiceConfig config = new McpServiceConfig();
        config.setId(id);
        config.setName("service");
        config.setBaseUrl("http://localhost");
        config.setToolDiscoveryPath("/tools");
        config.setToolInvokePath("/tools/call");
        config.setProtocol("legacy_http");
        config.setEnabled(false);
        return config;
    }
}
