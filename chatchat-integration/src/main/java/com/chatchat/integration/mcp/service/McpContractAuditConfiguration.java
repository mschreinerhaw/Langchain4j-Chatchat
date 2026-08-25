package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.audit.DatabaseMcpServiceContract;
import com.chatchat.common.mcp.audit.GenericMcpServiceContract;
import com.chatchat.common.mcp.audit.LinuxMcpServiceContract;
import com.chatchat.common.mcp.audit.McpContractAuditor;
import com.chatchat.common.mcp.audit.McpDomainServiceContract;
import com.chatchat.common.mcp.audit.StandardMcpContractAuditor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires common Runtime OS contract implementations as replaceable Spring strategies. */
@Configuration
public class McpContractAuditConfiguration {
    @Bean McpContractAuditor standardMcpContractAuditor() { return new StandardMcpContractAuditor(); }
    @Bean McpDomainServiceContract databaseMcpServiceContract() { return new DatabaseMcpServiceContract(); }
    @Bean McpDomainServiceContract linuxMcpServiceContract() { return new LinuxMcpServiceContract(); }
    @Bean McpDomainServiceContract genericMcpServiceContract() { return new GenericMcpServiceContract(); }
}
