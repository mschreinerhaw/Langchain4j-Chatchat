package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.List;

/** Secret-free contract evidence used to reproduce every audit decision. */
public record McpContractEvidence(
    String serviceId,
    String toolName,
    String domainCode,
    String domainContractId,
    String domainContractVersion,
    McpToolDescriptor observedContract,
    List<String> satisfiedPaths,
    List<String> missingPaths,
    boolean normalizedResultAvailable,
    boolean rawResultAvailable
) {
    public McpContractEvidence {
        satisfiedPaths = satisfiedPaths == null ? List.of() : List.copyOf(satisfiedPaths);
        missingPaths = missingPaths == null ? List.of() : List.copyOf(missingPaths);
    }
}
