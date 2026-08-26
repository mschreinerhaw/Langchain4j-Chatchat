package com.chatchat.api.service;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.kernel.KernelHealth;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

/** Stable API-side access point; API code depends only on the common MCP contract. */
@Service
public class McpRuntimeAccessService {
    private final McpRuntimeTransportPort transport;

    public McpRuntimeAccessService(
        @Qualifier("mcpRuntimeTransportPort") McpRuntimeTransportPort transport
    ) {
        this.transport = transport;
    }

    public List<McpServiceDescriptor> services() { return transport.services(); }
    public List<McpToolDescriptor> tools(McpToolQuery query) { return transport.tools(query); }
    public McpServiceResult invoke(McpServiceCall call) { return transport.invoke(call); }
    public McpResultRepairResult repair(McpResultRepairRequest request) { return transport.repair(request); }
    public void refresh() { transport.refresh(); }
    public List<McpDomainContractDescriptor> contracts() { return transport.contracts(); }
    public McpContractAuditReport audit(McpContractAuditRequest request) { return transport.audit(request); }
    public KernelHealth health() { return transport.health(); }
}
