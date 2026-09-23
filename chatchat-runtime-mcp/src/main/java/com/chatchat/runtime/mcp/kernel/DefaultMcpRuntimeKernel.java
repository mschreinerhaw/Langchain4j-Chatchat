package com.chatchat.runtime.mcp.kernel;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.kernel.KernelOperationalState;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.runtime.mcp.workflow.McpExecutionWorkflow;
import com.chatchat.runtime.mcp.workflow.McpExecutionWorkflowExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime OS MCP kernel owning catalog lifecycle, health and execution-workflow dispatch. */
@Slf4j
public class DefaultMcpRuntimeKernel implements McpRuntimeKernel {
    private final McpServiceDirectory directory;
    private final McpRuntimeContractService contracts;
    private final McpExecutionWorkflow executionWorkflow;
    private final AtomicLong revision = new AtomicLong();
    private volatile KernelOperationalState operationalState = KernelOperationalState.STARTING;
    private volatile long lastSuccessfulRefreshAt;
    private volatile String lastFailure;

    public DefaultMcpRuntimeKernel(McpServiceDirectory directory,
                                   McpRuntimeContractService contracts) {
        this(directory, contracts, List.of());
    }

    public DefaultMcpRuntimeKernel(McpServiceDirectory directory,
                                   McpRuntimeContractService contracts,
                                   List<McpExecutionWorkflowExtension> executionExtensions) {
        this.directory = directory;
        this.contracts = contracts;
        this.executionWorkflow = new McpExecutionWorkflow(directory, contracts, this::refresh,
            executionExtensions);
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
        return executionWorkflow.execute(call, workflowScope(call));
    }

    private KernelDataScope workflowScope(McpServiceCall call) {
        Map<String, Object> context = call.context();
        return new KernelDataScope(text(context.get("tenantId")), text(context.get("userId")),
            call.requestId(), text(context.get("conversationId")), text(context.get("runId")),
            text(context.get("environment")), Map.of());
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
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
