package com.chatchat.common.mcp.runtime;

import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.kernel.KernelComponentDescriptor;
import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelProtocol;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.kernel.KernelStatus;
import com.chatchat.common.kernel.RuntimeOsKernel;

import java.util.Map;
import java.util.Set;

/**
 * Runtime OS kernel contract for MCP discovery, invocation, repair and audit.
 *
 * <p>Every transport and orchestration layer must depend on this interface instead
 * of invoking an MCP provider, registry or transport directly.</p>
 */
public interface McpRuntimeKernel extends RuntimeOsKernel<McpServiceCall, McpServiceResult>,
    McpServiceDirectory, McpRuntimeContractService {
    String KERNEL_PROTOCOL_VERSION = "runtime_os_mcp_kernel.v1";

    @Override
    default KernelComponentDescriptor kernelDescriptor() {
        return new KernelComponentDescriptor("runtime-os/mcp", "mcp-runtime-kernel", "1",
            Set.of("discover", "invoke", "repair", "refresh", "audit"));
    }

    @Override
    default KernelProtocol kernelProtocol() {
        return KernelProtocolCatalog.MCP_BRIDGE;
    }

    @Override
    default KernelDataBoundary kernelDataBoundary() {
        return KernelProtocolCatalog.MCP_BOUNDARY;
    }

    @Override
    default McpServiceResult executeKernel(McpServiceCall call, KernelDataScope scope) {
        return invoke(call);
    }

    /** Canonical convenience entry used by API and orchestration adapters. */
    default McpServiceResult execute(McpServiceCall call) {
        if (call == null) throw new IllegalArgumentException("call is required");
        Map<String, Object> context = call.context();
        KernelDataScope scope = new KernelDataScope(
            text(context.get("tenantId"), "system"), text(context.get("userId"), null),
            call.requestId(), text(context.get("conversationId"), null),
            text(context.get("runId"), null), text(context.get("environment"), null), Map.of());
        KernelInvocation<McpServiceCall> invocation = KernelInvocation.of("mcp.invoke", kernelProtocol(), scope,
            Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE, KernelDataDomain.EVENTS), call);
        KernelResult<McpServiceResult> result = invoke(invocation);
        if (result.successful()) return result.data();
        McpServiceResultStatus status = result.status() == KernelStatus.REJECTED
            ? McpServiceResultStatus.REJECTED : McpServiceResultStatus.FAILED;
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(), status,
            null, null, result.errorCode(), result.errorMessage(), false, "REVIEW_KERNEL_CONTRACT",
            Map.of("kernelAbiVersion", result.abiVersion(), "kernelInvocationId", result.invocationId()), 0);
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
