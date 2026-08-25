package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Base implementation for token-addressable service domains without framework dependencies. */
public abstract class AbstractMcpDomainServiceContract implements McpDomainServiceContract {
    private final Set<String> domainTokens;

    protected AbstractMcpDomainServiceContract(String... domainTokens) {
        this.domainTokens = new LinkedHashSet<>(Arrays.stream(domainTokens == null ? new String[0] : domainTokens)
            .filter(value -> value != null && !value.isBlank()).map(this::normalize).toList());
    }

    @Override
    public boolean supports(McpToolDescriptor tool) {
        if (tool == null) return false;
        String searchable = normalize(String.join(" ", tool.localToolName(), tool.remoteToolName(),
            tool.capabilityCode(), String.valueOf(tool.metadata().get("backendServiceType")),
            String.valueOf(tool.metadata().get("categories")), String.valueOf(tool.metadata().get("tags")),
            String.valueOf(tool.metadata().get("contractMeta"))));
        return domainTokens.stream().anyMatch(searchable::contains);
    }

    @Override public String contractVersion() { return "mcp_domain_contract.v1"; }

    protected McpContractRequirement required(McpContractSource source, String path, String code,
                                              String description, String recovery) {
        return new McpContractRequirement(source, path, code, McpContractSeverity.ERROR, description, recovery);
    }

    private String normalize(String value) { return value.toLowerCase(Locale.ROOT).replace('-', '_'); }
}
