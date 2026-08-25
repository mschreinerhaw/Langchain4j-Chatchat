package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpResultRepairer;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceProvider;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicMcpServiceDirectoryTest {
    @Test
    void discoversProviderAndInvokesThroughCommonContract() {
        McpServiceProvider provider = provider("docker", "docker_ps");
        DynamicMcpServiceDirectory directory = directory(List.of(provider), List.of());
        McpServiceCall call = new McpServiceCall(null, "r1", "docker", "docker_ps", Map.of(), Map.of(), 0);

        assertThat(directory.services()).extracting(McpServiceDescriptor::serviceId).containsExactly("docker");
        assertThat(directory.tools(McpToolQuery.all())).hasSize(1);
        assertThat(directory.invoke(call).rawData()).isEqualTo(Map.of("stdout", "docker output"));
    }

    @Test
    void returnsRecoverableNotFoundResultWhenNoProviderOwnsTool() {
        DynamicMcpServiceDirectory directory = directory(List.of(), List.of());
        McpServiceResult result = directory.invoke(new McpServiceCall(null, "r1", "missing", "tool", Map.of(), Map.of(), 0));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.NOT_FOUND);
        assertThat(result.recoveryAction()).isEqualTo("REFRESH_OR_DISCOVER");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void injectsDiscoveredOutputSchemaIntoRepairRequest() {
        AtomicReference<McpResultRepairRequest> captured = new AtomicReference<>();
        McpResultRepairer repairer = new McpResultRepairer() {
            public String repairerId() { return "capture"; }
            public boolean supports(McpResultRepairRequest request) { return true; }
            public McpResultRepairResult repair(McpResultRepairRequest request) {
                captured.set(request);
                return new McpResultRepairResult(null, request.requestId(), request.serviceId(), request.toolName(),
                    McpServiceResultStatus.REPAIRED, request.rawResult(), request.rawResult(), Map.of(), "ok");
            }
        };
        DynamicMcpServiceDirectory directory = directory(List.of(provider("docker", "docker_ps")), List.of(repairer));

        directory.repair(new McpResultRepairRequest(null, "r1", "docker", "docker_ps", "stdout", "failed", Map.of(), Map.of()));

        assertThat(captured.get().expectedOutputSchema()).containsEntry("type", "object");
    }

    private McpServiceProvider provider(String serviceId, String toolName) {
        McpToolDescriptor tool = new McpToolDescriptor(serviceId, toolName, "ps", "", "diagnostic",
            Map.of(), Map.of("type", "object"), Map.of(), Map.of());
        return new McpServiceProvider() {
            public String providerId() { return "test"; }
            public Collection<McpServiceDescriptor> services() {
                return List.of(new McpServiceDescriptor(serviceId, serviceId, providerId(), "test", true, Map.of()));
            }
            public Collection<McpToolDescriptor> tools(McpToolQuery query) { return List.of(tool); }
            public boolean supports(String requestedService, String requestedTool) {
                return serviceId.equals(requestedService) && (toolName.equals(requestedTool) || "ps".equals(requestedTool));
            }
            public McpServiceResult invoke(McpServiceCall call) {
                Map<String, Object> raw = Map.of("stdout", "docker output");
                return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
                    McpServiceResultStatus.SUCCESS, raw, raw, null, null, false, null, Map.of(), 0);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private DynamicMcpServiceDirectory directory(List<McpServiceProvider> providers, List<McpResultRepairer> repairers) {
        ObjectProvider<McpServiceProvider> providerBeans = mock(ObjectProvider.class);
        ObjectProvider<McpResultRepairer> repairerBeans = mock(ObjectProvider.class);
        when(providerBeans.orderedStream()).thenAnswer(ignored -> providers.stream());
        when(repairerBeans.stream()).thenAnswer(ignored -> repairers.stream());
        return new DynamicMcpServiceDirectory(providerBeans, repairerBeans);
    }
}
