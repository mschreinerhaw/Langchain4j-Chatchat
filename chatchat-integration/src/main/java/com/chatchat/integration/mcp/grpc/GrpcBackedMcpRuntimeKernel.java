package com.chatchat.integration.mcp.grpc;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;

import java.util.List;

/** Runtime Kernel proxy that preserves the Kernel ABI while executing through gRPC. */
public final class GrpcBackedMcpRuntimeKernel implements McpRuntimeKernel {
    private final McpRuntimeTransportPort transport;

    public GrpcBackedMcpRuntimeKernel(McpRuntimeTransportPort transport) {
        this.transport = transport;
    }

    @Override public List<McpServiceDescriptor> services() { return transport.services(); }
    @Override public List<McpToolDescriptor> tools(McpToolQuery query) { return transport.tools(query); }
    @Override public McpServiceResult invoke(McpServiceCall call) { return transport.invoke(call); }
    @Override public McpServiceResult executeKernel(McpServiceCall call, KernelDataScope scope) {
        return transport.invoke(call);
    }
    @Override public McpResultRepairResult repair(McpResultRepairRequest request) {
        return transport.repair(request);
    }
    @Override public void refresh() { transport.refresh(); }
    @Override public List<McpDomainContractDescriptor> contracts() { return transport.contracts(); }
    @Override public McpContractAuditReport audit(McpContractAuditRequest request) {
        return transport.audit(request);
    }
    @Override public KernelHealth kernelHealth() { return transport.health(); }
}
