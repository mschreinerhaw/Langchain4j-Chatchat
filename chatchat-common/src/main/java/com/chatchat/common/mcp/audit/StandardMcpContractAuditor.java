package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic common implementation producing evidence for every finding and recovery action. */
public final class StandardMcpContractAuditor implements McpContractAuditor {
    private static final String GENERIC_CONTRACT = "runtime-os/generic-mcp-service";

    @Override
    public McpContractAuditReport audit(McpContractAuditRequest request,
                                        Collection<McpServiceDescriptor> services,
                                        Collection<McpToolDescriptor> tools,
                                        Collection<McpDomainServiceContract> domainContracts) {
        McpContractAuditRequest effective = request == null
            ? new McpContractAuditRequest(null, null, null, null, null) : request;
        List<McpContractFinding> findings = new ArrayList<>();
        List<McpContractEvidence> evidence = new ArrayList<>();
        List<McpServiceDescriptor> selectedServices = safe(services).stream()
            .filter(service -> effective.serviceId() == null || effective.serviceId().equals(service.serviceId())).toList();
        if (effective.serviceId() != null && selectedServices.isEmpty()) {
            findings.add(finding(McpContractSeverity.ERROR, "MCP_SERVICE_NOT_FOUND", effective.serviceId(),
                effective.toolName(), null, null, "$", "Requested MCP service was not discovered", "absent",
                "REFRESH_SERVICE_DIRECTORY"));
        }
        List<McpToolDescriptor> selectedTools = safe(tools).stream()
            .filter(tool -> effective.serviceId() == null || effective.serviceId().equals(tool.serviceId()))
            .filter(tool -> effective.toolName() == null || effective.toolName().equals(tool.localToolName())
                || effective.toolName().equals(tool.remoteToolName())).toList();
        if (selectedTools.isEmpty()) {
            findings.add(finding(McpContractSeverity.ERROR, "MCP_TOOL_NOT_FOUND", effective.serviceId(),
                effective.toolName(), null, null, "$", "Requested MCP tool contract was not discovered", "absent",
                "REFRESH_OR_SEARCH_TOOL_CONTRACT"));
        }
        List<McpDomainServiceContract> contracts = safe(domainContracts);
        for (McpToolDescriptor tool : selectedTools) {
            List<McpDomainServiceContract> matched = contracts.stream()
                .filter(contract -> !GENERIC_CONTRACT.equals(contract.contractId())).filter(contract -> contract.supports(tool)).toList();
            if (matched.isEmpty()) matched = contracts.stream().filter(contract -> GENERIC_CONTRACT.equals(contract.contractId())).toList();
            if (matched.isEmpty()) {
                findings.add(finding(McpContractSeverity.ERROR, "MCP_DOMAIN_CONTRACT_NOT_FOUND", tool.serviceId(),
                    tool.localToolName(), null, null, "$", "No domain contract can audit this MCP tool", "absent",
                    "REGISTER_DOMAIN_CONTRACT"));
                continue;
            }
            for (McpDomainServiceContract contract : matched) {
                auditTool(effective, tool, contract, findings, evidence);
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (McpContractSeverity severity : McpContractSeverity.values()) {
            long count = findings.stream().filter(finding -> finding.severity() == severity).count();
            if (count > 0) counts.put(severity.name(), count);
        }
        boolean compliant = findings.stream().noneMatch(finding -> finding.severity() == McpContractSeverity.ERROR
            || finding.severity() == McpContractSeverity.CRITICAL);
        return new McpContractAuditReport(null, compliant, evidence, findings, counts, 0);
    }

    private void auditTool(McpContractAuditRequest request, McpToolDescriptor tool,
                           McpDomainServiceContract contract, List<McpContractFinding> findings,
                           List<McpContractEvidence> evidence) {
        List<String> satisfied = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (McpContractRequirement requirement : contract.requirements()) {
            Object source = source(tool, request.executionResult(), requirement.source());
            Object observed = valueAt(source, requirement.path());
            String evidencePath = requirement.source() + ":" + requirement.path();
            if (present(observed)) {
                satisfied.add(evidencePath);
            } else {
                missing.add(evidencePath);
                findings.add(finding(requirement.severity(), requirement.code(), tool.serviceId(), tool.localToolName(),
                    contract.domainCode(), requirement.source(), requirement.path(), requirement.description(),
                    summarize(observed), requirement.recoveryAction()));
            }
        }
        for (String argument : request.requiredArguments()) {
            Object observed = valueAt(tool.inputSchema(), "properties." + argument);
            if (!present(observed)) {
                missing.add("INPUT_SCHEMA:properties." + argument);
                findings.add(finding(McpContractSeverity.ERROR, "MCP_TEMPLATE_PARAMETER_NOT_DECLARED", tool.serviceId(),
                    tool.localToolName(), contract.domainCode(), McpContractSource.INPUT_SCHEMA,
                    "properties." + argument, "Required template parameter is absent from the tool schema", "absent",
                    "REDISCOVER_TEMPLATE_PARAMETERS"));
            }
        }
        if (request.templateId() != null && !containsValue(tool.metadata(), request.templateId())) {
            missing.add("METADATA:templateId=" + request.templateId());
            findings.add(finding(McpContractSeverity.ERROR, "MCP_TEMPLATE_ID_NOT_DISCOVERED", tool.serviceId(),
                tool.localToolName(), contract.domainCode(), McpContractSource.METADATA, "contractMeta",
                "Requested templateId is not present in discovery evidence", request.templateId(),
                "REDISCOVER_TEMPLATE_AND_REBIND"));
        }
        McpServiceResult result = request.executionResult();
        boolean resultBound = result == null || (tool.serviceId().equals(result.serviceId())
            && (tool.localToolName().equals(result.toolName()) || tool.remoteToolName().equals(result.toolName())));
        if (!resultBound) {
            findings.add(finding(McpContractSeverity.CRITICAL, "MCP_RESULT_BINDING_MISMATCH", tool.serviceId(),
                tool.localToolName(), contract.domainCode(), McpContractSource.RAW_RESULT, "$",
                "Execution evidence belongs to a different service or tool", result.serviceId() + "/" + result.toolName(),
                "REBIND_RESULT_TO_INVOCATION"));
            result = null;
        }
        boolean normalized = result != null && result.data() != null;
        boolean raw = result != null && result.rawData() != null;
        if (result != null && !raw) {
            missing.add("RAW_RESULT:$");
            findings.add(finding(McpContractSeverity.CRITICAL, "MCP_RAW_RESULT_MISSING", tool.serviceId(),
                tool.localToolName(), contract.domainCode(), McpContractSource.RAW_RESULT, "$",
                "Execution evidence lost the original MCP payload", "null", "REPLAY_WITH_LOSSLESS_BRIDGE"));
        } else if (raw) {
            satisfied.add("RAW_RESULT:$");
        }
        evidence.add(new McpContractEvidence(tool.serviceId(), tool.localToolName(), contract.domainCode(),
            contract.contractId(), contract.contractVersion(), tool, satisfied, missing, normalized, raw));
    }

    private Object source(McpToolDescriptor tool, McpServiceResult result, McpContractSource source) {
        return switch (source) {
            case INPUT_SCHEMA -> tool.inputSchema();
            case OUTPUT_SCHEMA -> tool.outputSchema();
            case GOVERNANCE -> tool.governance();
            case METADATA -> tool.metadata();
            case NORMALIZED_RESULT -> result == null ? null : result.data();
            case RAW_RESULT -> result == null ? null : result.rawData();
        };
    }

    private Object valueAt(Object root, String path) {
        Object current = root;
        if ("$".equals(path)) return current;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment);
        }
        return current;
    }

    private boolean containsValue(Object value, String expected) {
        if (value == null) return false;
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(item -> containsValue(item, expected));
        if (value instanceof Collection<?> items) return items.stream().anyMatch(item -> containsValue(item, expected));
        return expected.equals(String.valueOf(value));
    }

    private boolean present(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Collection<?> items) return !items.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private String summarize(Object value) {
        if (value == null) return "absent";
        if (value instanceof Map<?, ?> map) return "map(keys=" + map.keySet() + ")";
        if (value instanceof Collection<?> items) return "collection(size=" + items.size() + ")";
        String text = String.valueOf(value);
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private McpContractFinding finding(McpContractSeverity severity, String code, String serviceId, String toolName,
                                       String domain, McpContractSource source, String path, String message,
                                       String observed, String recovery) {
        return new McpContractFinding(severity, code, serviceId, toolName, domain, source, path, message, observed, recovery);
    }

    private <T> List<T> safe(Collection<T> values) { return values == null ? List.of() : List.copyOf(values); }
}
