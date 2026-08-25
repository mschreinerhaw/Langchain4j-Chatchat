package com.chatchat.api.service;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/** Stable API-side access point; API code depends only on the common MCP contract. */
@Service
public class McpRuntimeAccessService {
    private final McpServiceDirectory directory;
    private final McpRuntimeContractService contractService;

    public McpRuntimeAccessService(McpServiceDirectory directory, McpRuntimeContractService contractService) {
        this.directory = directory;
        this.contractService = contractService;
    }

    public List<McpServiceDescriptor> services() { return directory.services(); }
    public List<McpToolDescriptor> tools(McpToolQuery query) { return directory.tools(query); }
    public McpServiceResult invoke(McpServiceCall call) { return directory.invoke(call); }
    public McpResultRepairResult repair(McpResultRepairRequest request) { return directory.repair(request); }
    public void refresh() { directory.refresh(); }
    public List<McpDomainContractDescriptor> contracts() { return contractService.contracts(); }
    public McpContractAuditReport audit(McpContractAuditRequest request) { return contractService.audit(request); }
}
