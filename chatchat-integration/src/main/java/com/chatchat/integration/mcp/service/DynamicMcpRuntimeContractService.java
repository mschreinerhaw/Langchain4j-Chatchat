package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpContractAuditor;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.audit.McpDomainServiceContract;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Audits the live service directory with the currently injected domain contract strategies. */
@Service
public class DynamicMcpRuntimeContractService implements McpRuntimeContractService {
    private final McpServiceDirectory directory;
    private final McpContractAuditor auditor;
    private final ObjectProvider<McpDomainServiceContract> contractBeans;

    public DynamicMcpRuntimeContractService(
                                            @Qualifier("dynamicMcpServiceDirectory") McpServiceDirectory directory,
                                            McpContractAuditor auditor,
                                            ObjectProvider<McpDomainServiceContract> contractBeans) {
        this.directory = directory;
        this.auditor = auditor;
        this.contractBeans = contractBeans;
    }

    @Override
    public List<McpDomainContractDescriptor> contracts() {
        return domainContracts().stream().map(McpDomainContractDescriptor::from).toList();
    }

    @Override
    public McpContractAuditReport audit(McpContractAuditRequest request) {
        McpContractAuditRequest effective = request == null
            ? new McpContractAuditRequest(null, null, null, null, null) : request;
        McpToolQuery query = new McpToolQuery(effective.serviceId(), null,
            effective.toolName() == null ? Set.of() : Set.of(effective.toolName()));
        return auditor.audit(effective, directory.services(), directory.tools(query), domainContracts());
    }

    private List<McpDomainServiceContract> domainContracts() {
        return contractBeans.orderedStream().sorted(Comparator.comparing(McpDomainServiceContract::contractId)).toList();
    }
}
