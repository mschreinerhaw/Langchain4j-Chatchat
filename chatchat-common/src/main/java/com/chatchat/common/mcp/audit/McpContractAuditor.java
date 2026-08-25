package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.Collection;

/** Pure Runtime OS audit interface; callers supply current discovery facts and injected domain contracts. */
public interface McpContractAuditor {
    McpContractAuditReport audit(McpContractAuditRequest request,
                                 Collection<McpServiceDescriptor> services,
                                 Collection<McpToolDescriptor> tools,
                                 Collection<McpDomainServiceContract> domainContracts);
}
