package com.chatchat.common.mcp.audit;

import java.util.List;

/** API-facing port for live MCP domain contracts and evidence-backed audits. */
public interface McpRuntimeContractService {
    List<McpDomainContractDescriptor> contracts();
    McpContractAuditReport audit(McpContractAuditRequest request);
}
