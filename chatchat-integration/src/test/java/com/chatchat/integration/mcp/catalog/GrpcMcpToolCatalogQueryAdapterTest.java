package com.chatchat.integration.mcp.catalog;

import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcMcpToolCatalogQueryAdapterTest {

    @Test
    void reusesTheCatalogSnapshotWithinTheConfiguredTtl() {
        McpRuntimeTransportPort transport = mock(McpRuntimeTransportPort.class);
        when(transport.tools(any(McpToolQuery.class))).thenReturn(List.of());
        GrpcMcpToolCatalogQueryAdapter adapter = new GrpcMcpToolCatalogQueryAdapter(transport, 60_000L);

        assertThat(adapter.registeredTools()).isEmpty();
        assertThat(adapter.registeredTools()).isEmpty();
        assertThat(adapter.registeredTools()).isEmpty();

        verify(transport, times(1)).tools(any(McpToolQuery.class));
    }
}
