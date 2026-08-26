package com.chatchat.runtime.mcp.kernel;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class DefaultMcpRuntimeKernelTest {

    @Test
    void rejectsInvocationWhenContractPreflightFails() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(contracts.audit(any())).thenReturn(report(false));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        McpServiceResult result = kernel.execute(call());

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("MCP_CONTRACT_PREFLIGHT_FAILED");
        verify(directory, never()).invoke(any());
    }

    @Test
    void repairsMissingNormalizedDataAndPreservesRawResult() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(contracts.audit(any())).thenReturn(report(true));
        Map<String, Object> raw = Map.of("content", List.of(Map.of("type", "text", "text", "container-a Up")));
        when(directory.invoke(any())).thenReturn(new McpServiceResult(null, "request-1", "docker", "docker_ps",
            McpServiceResultStatus.SUCCESS, null, raw, null, null, false, null, Map.of(), 0));
        when(directory.repair(any())).thenReturn(new McpResultRepairResult(null, "request-1", "docker", "docker_ps",
            McpServiceResultStatus.REPAIRED, Map.of("text", "container-a Up"), raw,
            Map.of("rawPreserved", true), "normalized"));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        McpServiceResult result = kernel.execute(call());

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REPAIRED);
        assertThat(result.data()).isEqualTo(Map.of("text", "container-a Up"));
        assertThat(result.rawData()).isSameAs(raw);
        assertThat(result.metadata()).containsKeys("kernelProtocolVersion", "preflightAudit", "postflightAudit", "automaticRepair");
    }

    @Test
    void reportsDegradedHealthWithoutFailingApplicationStartup() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.services()).thenReturn(List.of());
        when(directory.tools(any())).thenReturn(List.of());
        when(contracts.contracts()).thenReturn(List.of());
        doThrow(new IllegalStateException("remote registry unavailable")).when(directory).refresh();
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        kernel.initialize();

        assertThat(kernel.kernelHealth().state().name()).isEqualTo("DEGRADED");
        assertThat(kernel.kernelHealth().lastFailure()).contains("remote registry unavailable");
    }

    private McpServiceCall call() {
        return new McpServiceCall(null, "request-1", "docker", "docker_ps", Map.of(), Map.of(), 0);
    }

    private McpContractAuditReport report(boolean compliant) {
        return new McpContractAuditReport(null, compliant, List.of(), List.of(), Map.of(), 0);
    }
}
