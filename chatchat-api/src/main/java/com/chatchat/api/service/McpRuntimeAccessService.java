package com.chatchat.api.service;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.kernel.KernelHealth;
import org.springframework.stereotype.Service;

import java.util.List;

/** Stable API-side access point; API code depends only on the common MCP contract. */
@Service
public class McpRuntimeAccessService {
    private final McpRuntimeKernel kernel;

    public McpRuntimeAccessService(McpRuntimeKernel kernel) {
        this.kernel = kernel;
    }

    public List<McpServiceDescriptor> services() { return kernel.services(); }
    public List<McpToolDescriptor> tools(McpToolQuery query) { return kernel.tools(query); }
    public McpServiceResult invoke(McpServiceCall call) { return kernel.execute(call); }
    public McpResultRepairResult repair(McpResultRepairRequest request) { return kernel.repair(request); }
    public void refresh() { kernel.refresh(); }
    public List<McpDomainContractDescriptor> contracts() { return kernel.contracts(); }
    public McpContractAuditReport audit(McpContractAuditRequest request) { return kernel.audit(request); }
    public KernelHealth health() { return kernel.kernelHealth(); }
}
