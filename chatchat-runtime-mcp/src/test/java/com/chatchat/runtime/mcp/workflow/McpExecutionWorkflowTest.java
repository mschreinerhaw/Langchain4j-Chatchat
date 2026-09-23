package com.chatchat.runtime.mcp.workflow;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractFinding;
import com.chatchat.common.mcp.audit.McpContractSeverity;
import com.chatchat.common.mcp.audit.McpContractSource;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpExecutionWorkflowTest {

    @Test
    void executesInternalAndExternalProvidersThroughTheSameLifecycle() {
        for (String transport : List.of("in-process", "streamable-http")) {
            McpServiceDirectory directory = mock(McpServiceDirectory.class);
            McpRuntimeContractService contracts = compliantContracts();
            String serviceId = transport.equals("in-process") ? "local" : "remote";
            when(directory.services()).thenReturn(List.of(new McpServiceDescriptor(
                serviceId, serviceId, "provider-" + serviceId, transport, true, Map.of())));
            when(directory.tools(any())).thenReturn(List.of(tool(serviceId)));
            when(directory.invoke(any())).thenAnswer(invocation -> {
                McpServiceCall call = invocation.getArgument(0);
                return success(call, Map.of("value", serviceId), Map.of());
            });
            McpExecutionWorkflow workflow = new McpExecutionWorkflow(directory, contracts, () -> { });

            McpServiceResult result = workflow.execute(call(serviceId));

            assertThat(result.successful()).isTrue();
            assertThat(workflowTrace(result))
                .containsEntry("workflowId", McpExecutionWorkflow.WORKFLOW_ID)
                .containsEntry("toolDiscovered", true)
                .containsEntry("route", transport.equals("in-process") ? "INTERNAL" : "PROVIDER")
                .containsEntry("transport", transport);
        }
    }

    @Test
    void appliesOrderedExtensionsWithoutCouplingThemToTransport() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = compliantContracts();
        when(directory.services()).thenReturn(List.of(new McpServiceDescriptor(
            "remote", "remote", "external-provider", "stdio", true, Map.of())));
        when(directory.tools(any())).thenReturn(List.of(tool("remote")));
        AtomicReference<McpServiceCall> invoked = new AtomicReference<>();
        when(directory.invoke(any())).thenAnswer(invocation -> {
            McpServiceCall call = invocation.getArgument(0);
            invoked.set(call);
            return success(call, Map.of("ok", true), Map.of());
        });
        McpExecutionWorkflowExtension extension = new McpExecutionWorkflowExtension() {
            @Override
            public McpServiceCall beforeInvoke(McpExecutionWorkflow.Context context, McpServiceCall call) {
                Map<String, Object> enriched = new LinkedHashMap<>(call.context());
                enriched.put("policyApplied", true);
                return call.withContext(enriched);
            }

            @Override
            public McpServiceResult afterInvoke(McpExecutionWorkflow.Context context, McpServiceCall call,
                                                McpServiceResult result) {
                Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
                metadata.put("extensionObserved", true);
                return success(call, result.data(), metadata);
            }
        };
        McpExecutionWorkflow workflow = new McpExecutionWorkflow(
            directory, contracts, () -> { }, List.of(extension));

        McpServiceResult result = workflow.execute(call("remote"));

        assertThat(invoked.get().context()).containsEntry("policyApplied", true);
        assertThat(result.metadata()).containsEntry("extensionObserved", true);
        assertThat(workflowTrace(result).get("extensions").toString())
            .contains(extension.getClass().getName());
    }

    @Test
    void refreshesAndRediscoversOnceWhenPreflightReportsAStaleCatalog() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.services()).thenReturn(List.of());
        when(directory.tools(any())).thenReturn(List.of(), List.of(tool("remote")));
        McpContractFinding missing = new McpContractFinding(McpContractSeverity.ERROR,
            "MCP_TOOL_NOT_FOUND", "remote", "weather", "query", McpContractSource.METADATA,
            "toolName", "registered tool", "missing", "REFRESH_OR_DISCOVER");
        when(contracts.audit(any())).thenReturn(
            new McpContractAuditReport(null, false, List.of(), List.of(missing), Map.of("ERROR", 1L), 0),
            new McpContractAuditReport(null, true, List.of(), List.of(), Map.of(), 0),
            new McpContractAuditReport(null, true, List.of(), List.of(), Map.of(), 0));
        when(directory.invoke(any())).thenAnswer(invocation -> success(
            invocation.getArgument(0), Map.of("ok", true), Map.of()));
        AtomicInteger refreshes = new AtomicInteger();
        McpExecutionWorkflow workflow = new McpExecutionWorkflow(
            directory, contracts, refreshes::incrementAndGet);

        McpServiceResult result = workflow.execute(call("remote"));

        assertThat(result.successful()).isTrue();
        assertThat(refreshes).hasValue(1);
        assertThat(result.metadata()).containsEntry("discoveryRefreshAttempted", true);
        assertThat(workflowTrace(result)).containsEntry("toolDiscovered", true);
    }

    private McpRuntimeContractService compliantContracts() {
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(contracts.audit(any())).thenReturn(new McpContractAuditReport(
            null, true, List.of(), List.of(), Map.of(), 0));
        return contracts;
    }

    private McpToolDescriptor tool(String serviceId) {
        return new McpToolDescriptor(serviceId, "weather", "weather", "", "query",
            Map.of("type", "object"), Map.of("type", "object"), Map.of(), Map.of());
    }

    private McpServiceCall call(String serviceId) {
        return new McpServiceCall(null, "request-1", serviceId, "weather",
            Map.of("city", "Shanghai"), Map.of("tenantId", "tenant-1"), 0);
    }

    private McpServiceResult success(McpServiceCall call, Object data, Map<String, Object> metadata) {
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            McpServiceResultStatus.SUCCESS, data, data, null, null, false, null, metadata, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> workflowTrace(McpServiceResult result) {
        return (Map<String, Object>) result.metadata().get("mcpExecutionWorkflow");
    }
}
