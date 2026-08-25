package com.chatchat.api.service;

import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRuntimeAccessServiceTest {
    @Test
    void apiLayerDependsOnCommonDirectoryAndExposesInjectedServices() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        when(directory.services()).thenReturn(List.of(
            new McpServiceDescriptor("docker", "Docker", "provider", "stdio", true, Map.of())));

        McpRuntimeAccessService service = new McpRuntimeAccessService(directory, mock(McpRuntimeContractService.class));

        assertThat(service.services()).extracting(McpServiceDescriptor::serviceId).containsExactly("docker");
    }
}
