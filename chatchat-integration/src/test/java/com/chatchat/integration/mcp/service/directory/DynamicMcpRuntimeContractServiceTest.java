package com.chatchat.integration.mcp.service.directory;

import com.chatchat.integration.mcp.service.directory.DynamicMcpRuntimeContractService;

import com.chatchat.common.mcp.audit.GenericMcpServiceContract;
import com.chatchat.common.mcp.audit.McpContractAuditor;
import com.chatchat.common.mcp.audit.McpDomainServiceContract;
import com.chatchat.common.mcp.audit.StandardMcpContractAuditor;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicMcpRuntimeContractServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void usesLiveDirectoryAndDynamicallyInjectedContracts() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        when(directory.services()).thenReturn(List.of(
            new McpServiceDescriptor("future", "Future", "provider", "http", true, Map.of())));
        when(directory.tools(any(McpToolQuery.class))).thenReturn(List.of(
            new McpToolDescriptor("future", "custom", "custom", "", "custom",
                Map.of("type", "object"), Map.of("type", "object"), Map.of("riskLevel", "low"),
                Map.of("contractVersion", "mcp_tool_contract.v1"))));
        ObjectProvider<McpDomainServiceContract> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenAnswer(ignored -> java.util.stream.Stream.of(new GenericMcpServiceContract()));
        McpContractAuditor auditor = new StandardMcpContractAuditor();
        DynamicMcpRuntimeContractService service = new DynamicMcpRuntimeContractService(directory, auditor, beans);

        assertThat(service.contracts()).extracting(item -> item.domainCode()).containsExactly("generic");
        assertThat(service.audit(null).compliant()).isTrue();
    }
}
