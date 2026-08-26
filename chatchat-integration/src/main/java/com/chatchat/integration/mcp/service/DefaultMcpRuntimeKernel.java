package com.chatchat.integration.mcp.service;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Default Runtime OS MCP kernel: discover, audit, invoke, repair and audit again. */
@Service
public class DefaultMcpRuntimeKernel implements McpRuntimeKernel {
    private final McpServiceDirectory directory;
    private final DynamicMcpRuntimeContractService contracts;

    public DefaultMcpRuntimeKernel(DynamicMcpServiceDirectory directory,
                                   DynamicMcpRuntimeContractService contracts) {
        this.directory = directory;
        this.contracts = contracts;
    }

    /** Kernel owns provider lifecycle; adapters must not self-initialize independently. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        refresh();
    }

    @Override public List<McpServiceDescriptor> services() { return directory.services(); }
    @Override public List<McpToolDescriptor> tools(McpToolQuery query) { return directory.tools(query); }
    @Override public McpResultRepairResult repair(McpResultRepairRequest request) { return directory.repair(request); }
    @Override public void refresh() { directory.refresh(); }
    @Override public List<McpDomainContractDescriptor> contracts() { return contracts.contracts(); }
    @Override public McpContractAuditReport audit(McpContractAuditRequest request) { return contracts.audit(request); }

    @Override
    public McpServiceResult invoke(McpServiceCall call) {
        if (call == null) throw new IllegalArgumentException("call is required");
        McpContractAuditRequest preflightRequest = new McpContractAuditRequest(
            call.serviceId(), call.toolName(), text(call.context().get("templateId")),
            stringSet(call.context().get("requiredArguments")), null);
        McpContractAuditReport preflight = audit(preflightRequest);
        boolean discoveryRefreshAttempted = false;
        if (hasFinding(preflight, "MCP_SERVICE_NOT_FOUND", "MCP_TOOL_NOT_FOUND")) {
            directory.refresh();
            discoveryRefreshAttempted = true;
            preflight = audit(preflightRequest);
        }
        if (!preflight.compliant()) {
            Map<String, Object> rejectionMetadata = new LinkedHashMap<>(kernelMetadata(preflight, null, null));
            rejectionMetadata.put("discoveryRefreshAttempted", discoveryRefreshAttempted);
            return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
                McpServiceResultStatus.REJECTED, null, null, "MCP_CONTRACT_PREFLIGHT_FAILED",
                "MCP invocation rejected by Runtime OS contract preflight", false,
                "REPAIR_CONTRACT_OR_REDISCOVER", rejectionMetadata, 0);
        }

        McpServiceResult invoked = directory.invoke(call);
        McpResultRepairResult repaired = null;
        if (invoked.rawData() != null && invoked.data() == null && repairable(invoked)) {
            repaired = directory.repair(new McpResultRepairRequest(null, invoked.requestId(), invoked.serviceId(),
                invoked.toolName(), invoked.rawData(), invoked.errorMessage(), Map.of(), call.context()));
            if (repaired.normalizedData() != null) {
                invoked = new McpServiceResult(null, invoked.requestId(), invoked.serviceId(), invoked.toolName(),
                    repaired.status(), repaired.normalizedData(), invoked.rawData(), null,
                    null, false, null, invoked.metadata(), 0);
            }
        }
        McpContractAuditReport postflight = audit(new McpContractAuditRequest(
            call.serviceId(), call.toolName(), text(call.context().get("templateId")),
            stringSet(call.context().get("requiredArguments")), invoked));
        Map<String, Object> metadata = new LinkedHashMap<>(kernelMetadata(preflight, postflight, repaired));
        metadata.put("discoveryRefreshAttempted", discoveryRefreshAttempted);
        return copyWithMetadata(invoked, metadata);
    }

    private McpServiceResult copyWithMetadata(McpServiceResult result, Map<String, Object> kernelMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.putAll(kernelMetadata);
        return new McpServiceResult(null, result.requestId(), result.serviceId(), result.toolName(), result.status(),
            result.data(), result.rawData(), result.errorCode(), result.errorMessage(), result.retryable(),
            result.recoveryAction(), metadata, result.completedAt());
    }

    private Map<String, Object> kernelMetadata(McpContractAuditReport preflight,
                                                McpContractAuditReport postflight,
                                                McpResultRepairResult repair) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kernelProtocolVersion", KERNEL_PROTOCOL_VERSION);
        metadata.put("preflightAudit", preflight);
        if (postflight != null) metadata.put("postflightAudit", postflight);
        if (repair != null) metadata.put("automaticRepair", repair);
        return metadata;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        values.forEach(item -> { if (text(item) != null) result.add(text(item)); });
        return Set.copyOf(result);
    }

    private boolean repairable(McpServiceResult result) {
        if (result.successful()) return true;
        String action = text(result.recoveryAction());
        return action != null && action.toUpperCase(java.util.Locale.ROOT).contains("REPAIR");
    }

    private boolean hasFinding(McpContractAuditReport report, String... codes) {
        Set<String> expected = Set.of(codes);
        return report != null && report.findings().stream().anyMatch(finding -> expected.contains(finding.code()));
    }
}
