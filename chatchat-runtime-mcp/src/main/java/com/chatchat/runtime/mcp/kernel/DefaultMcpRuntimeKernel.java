package com.chatchat.runtime.mcp.kernel;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.contract.McpTemplateBindingEvidence;
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
import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.kernel.KernelOperationalState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Default Runtime OS MCP kernel: discover, audit, invoke, repair and audit again. */
@Slf4j
public class DefaultMcpRuntimeKernel implements McpRuntimeKernel {
    private final McpServiceDirectory directory;
    private final McpRuntimeContractService contracts;
    private final AtomicLong revision = new AtomicLong();
    private volatile KernelOperationalState operationalState = KernelOperationalState.STARTING;
    private volatile long lastSuccessfulRefreshAt;
    private volatile String lastFailure;

    public DefaultMcpRuntimeKernel(McpServiceDirectory directory,
                                   McpRuntimeContractService contracts) {
        this.directory = directory;
        this.contracts = contracts;
    }

    /** Kernel owns provider lifecycle; adapters must not self-initialize independently. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn("MCP Runtime OS kernel started in DEGRADED state: {}", failure.getMessage(), failure);
        }
    }

    @Override public List<McpServiceDescriptor> services() { return directory.services(); }
    @Override public List<McpToolDescriptor> tools(McpToolQuery query) { return directory.tools(query); }
    @Override public McpResultRepairResult repair(McpResultRepairRequest request) { return directory.repair(request); }
    @Override
    public void refresh() {
        try {
            directory.refresh();
            revision.incrementAndGet();
            lastSuccessfulRefreshAt = System.currentTimeMillis();
            lastFailure = null;
            operationalState = KernelOperationalState.READY;
        } catch (RuntimeException failure) {
            lastFailure = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            operationalState = KernelOperationalState.DEGRADED;
            throw failure;
        }
    }

    @Override
    public KernelHealth kernelHealth() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("serviceCount", safeCount(this::services));
        details.put("toolCount", safeCount(() -> tools(McpToolQuery.all())));
        details.put("contractCount", safeCount(this::contracts));
        details.put("protocol", kernelProtocol().id());
        return new KernelHealth(null, kernelDescriptor(), operationalState, revision.get(),
            lastSuccessfulRefreshAt, lastFailure, details, 0);
    }
    @Override public List<McpDomainContractDescriptor> contracts() { return contracts.contracts(); }
    @Override public McpContractAuditReport audit(McpContractAuditRequest request) { return contracts.audit(request); }

    @Override
    public McpServiceResult invoke(McpServiceCall call) {
        if (call == null) throw new IllegalArgumentException("call is required");
        String requestedTemplateId = text(call.context().get("templateId"));
        McpTemplateBindingEvidence templateBinding = McpTemplateBindingEvidence
            .from(call.context().get(McpTemplateBindingEvidence.CONTEXT_KEY))
            .filter(binding -> binding.authorizes(requestedTemplateId, call.toolName()))
            .orElse(null);
        McpContractAuditRequest preflightRequest = new McpContractAuditRequest(
            call.serviceId(), call.toolName(), templateBinding == null ? requestedTemplateId : null,
            stringSet(call.context().get("requiredArguments")), null);
        McpContractAuditReport preflight = audit(preflightRequest);
        boolean discoveryRefreshAttempted = false;
        if (hasFinding(preflight, "MCP_SERVICE_NOT_FOUND", "MCP_TOOL_NOT_FOUND")) {
            refresh();
            discoveryRefreshAttempted = true;
            preflight = audit(preflightRequest);
        }
        if (!preflight.compliant()) {
            Map<String, Object> rejectionMetadata = new LinkedHashMap<>(kernelMetadata(preflight, null, null));
            rejectionMetadata.put("discoveryRefreshAttempted", discoveryRefreshAttempted);
            rejectionMetadata.put("templateBindingValidation", templateBindingMetadata(
                requestedTemplateId, call.toolName(), templateBinding));
            List<String> findingCodes = preflight.findings().stream().map(finding -> finding.code()).distinct().toList();
            rejectionMetadata.put("preflightFindingCodes", findingCodes);
            String rejectionMessage = findingCodes.isEmpty()
                ? "MCP invocation rejected by Runtime OS contract preflight"
                : "MCP invocation rejected by Runtime OS contract preflight: " + String.join(",", findingCodes);
            return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
                McpServiceResultStatus.REJECTED, null, null, "MCP_CONTRACT_PREFLIGHT_FAILED",
                rejectionMessage, false,
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
            call.serviceId(), call.toolName(), templateBinding == null ? requestedTemplateId : null,
            stringSet(call.context().get("requiredArguments")), invoked));
        Map<String, Object> metadata = new LinkedHashMap<>(kernelMetadata(preflight, postflight, repaired));
        metadata.put("discoveryRefreshAttempted", discoveryRefreshAttempted);
        metadata.put("templateBindingValidation", templateBindingMetadata(
            requestedTemplateId, call.toolName(), templateBinding));
        return copyWithMetadata(invoked, metadata);
    }

    private Map<String, Object> templateBindingMetadata(String templateId, String toolName,
                                                         McpTemplateBindingEvidence binding) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", McpTemplateBindingEvidence.SCHEMA_VERSION);
        metadata.put("templateIdPresent", templateId != null);
        metadata.put("executorTool", toolName);
        metadata.put("runtimeEvidenceValidated", binding != null);
        if (binding != null) metadata.put("source", binding.source());
        return Map.copyOf(metadata);
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

    private int safeCount(java.util.function.Supplier<? extends java.util.Collection<?>> source) {
        try {
            java.util.Collection<?> values = source.get();
            return values == null ? 0 : values.size();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }
}
