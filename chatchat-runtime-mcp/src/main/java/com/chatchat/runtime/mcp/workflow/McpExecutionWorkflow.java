package com.chatchat.runtime.mcp.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
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
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transport-neutral MCP execution workflow from live tool discovery to the
 * verified canonical result returned to Runtime OS.
 *
 * <p>Local tools and external MCP servers enter through the same
 * {@link McpServiceDirectory}. The directory selects an {@code McpServiceProvider};
 * the workflow never depends on an MCP SDK, HTTP, SSE, or STDIO transport.</p>
 */
@Slf4j
public final class McpExecutionWorkflow extends AbstractStagedExecutionWorkflow<
    McpServiceCall, McpExecutionWorkflow.Analysis, McpExecutionWorkflow.Plan,
    McpExecutionWorkflow.Execution, McpServiceResult> {

    public static final String WORKFLOW_ID = "execute.mcp-tool.v1";

    private static final List<String> STEPS = List.of(
        "DISCOVER_SERVICE",
        "DISCOVER_TOOL_TEMPLATE",
        "VALIDATE_TEMPLATE_BINDING",
        "VALIDATE_TOOL_CONTRACT_SNAPSHOT",
        "CONTRACT_PREFLIGHT",
        "REFRESH_CATALOG_IF_STALE",
        "APPLY_EXECUTION_EXTENSIONS",
        "INVOKE_PROVIDER",
        "REPAIR_RESULT_IF_REQUIRED",
        "CONTRACT_POSTFLIGHT",
        "ASSEMBLE_RESULT"
    );

    private final McpServiceDirectory directory;
    private final McpRuntimeContractService contracts;
    private final Runnable catalogRefresh;
    private final List<McpExecutionWorkflowExtension> extensions;

    public McpExecutionWorkflow(McpServiceDirectory directory,
                                McpRuntimeContractService contracts,
                                Runnable catalogRefresh) {
        this(directory, contracts, catalogRefresh, List.of());
    }

    public McpExecutionWorkflow(McpServiceDirectory directory,
                                McpRuntimeContractService contracts,
                                Runnable catalogRefresh,
                                List<McpExecutionWorkflowExtension> extensions) {
        this.directory = java.util.Objects.requireNonNull(directory, "directory");
        this.contracts = java.util.Objects.requireNonNull(contracts, "contracts");
        this.catalogRefresh = catalogRefresh == null ? directory::refresh : catalogRefresh;
        this.extensions = extensions == null ? List.of() : extensions.stream()
            .sorted(Comparator.comparingInt(McpExecutionWorkflowExtension::order))
            .toList();
    }

    @Override public String workflowId() { return WORKFLOW_ID; }

    @Override
    protected void validateInput(McpServiceCall input, KernelDataScope scope) {
        if (input == null) throw new IllegalArgumentException("call is required");
    }

    @Override
    protected Analysis analyze(McpServiceCall call, KernelDataScope scope) {
        String requestedTemplateId = text(call.context().get("templateId"));
        McpTemplateBindingEvidence.ParseResult bindingParse = McpTemplateBindingEvidence
            .parse(call.context().get(McpTemplateBindingEvidence.CONTEXT_KEY));
        String propagatedInvalidReason = text(call.context().get(McpTemplateBindingEvidence.INVALID_REASON_KEY));
        McpTemplateBindingEvidence binding = bindingParse.evidence().orElse(null);

        McpServiceDescriptor service = discoverService(call.serviceId());
        McpToolDescriptor tool = discoverTool(call);
        McpServiceResult rejection = null;
        if (bindingParse.invalidReason() != null || propagatedInvalidReason != null) {
            rejection = invalidTemplateBinding(call, requestedTemplateId,
                propagatedInvalidReason == null ? bindingParse.invalidReason() : propagatedInvalidReason);
        } else if (binding != null && !binding.authorizes(requestedTemplateId, call.toolName())) {
            rejection = invalidTemplateBinding(call, requestedTemplateId,
                "binding does not authorize requested templateId/executorTool");
        } else {
            rejection = validateToolContractSnapshot(call, binding, tool);
        }
        return new Analysis(requestedTemplateId, binding, service, tool, rejection);
    }

    @Override
    protected Plan plan(McpServiceCall call, Analysis analysis, KernelDataScope scope) {
        String providerId = analysis.service() == null ? null : analysis.service().providerId();
        String transport = analysis.service() == null ? null : analysis.service().transport();
        String route = "in-process".equalsIgnoreCase(transport) ? "INTERNAL" : "PROVIDER";
        return new Plan(STEPS, providerId, transport, route);
    }

    @Override
    protected Execution executePlan(McpServiceCall call, Analysis analysis, Plan plan,
                                    KernelDataScope scope) {
        if (analysis.rejection() != null) {
            return new Execution(call, analysis.service(), analysis.tool(), analysis.rejection(), null, null,
                null, false, List.of());
        }

        McpContractAuditRequest preflightRequest = new McpContractAuditRequest(
            call.serviceId(), call.toolName(), analysis.binding() == null
                ? analysis.requestedTemplateId() : null,
            stringSet(call.context().get("requiredArguments")), null);
        McpContractAuditReport preflight = contracts.audit(preflightRequest);
        boolean refreshAttempted = false;
        McpServiceDescriptor selectedService = analysis.service();
        McpToolDescriptor selectedTool = analysis.tool();
        if (hasFinding(preflight, "MCP_SERVICE_NOT_FOUND", "MCP_TOOL_NOT_FOUND")) {
            catalogRefresh.run();
            refreshAttempted = true;
            selectedService = discoverService(call.serviceId());
            selectedTool = discoverTool(call);
            preflight = contracts.audit(preflightRequest);
        }
        if (!preflight.compliant()) {
            return new Execution(call, selectedService, selectedTool,
                contractRejection(call, analysis, preflight, refreshAttempted),
                preflight, null, null, refreshAttempted, List.of());
        }

        Context context = new Context(scope, selectedService, selectedTool, route(selectedService),
            analysis.requestedTemplateId());
        McpServiceCall effectiveCall = call;
        List<String> appliedExtensions = new ArrayList<>();
        for (McpExecutionWorkflowExtension extension : extensions) {
            if (!extension.supports(context)) continue;
            McpServiceCall prepared = extension.beforeInvoke(context, effectiveCall);
            effectiveCall = requireSameTarget(call, prepared, extension);
            appliedExtensions.add(extension.getClass().getName());
        }

        McpServiceResult invoked = directory.invoke(effectiveCall);
        for (int index = extensions.size() - 1; index >= 0; index--) {
            McpExecutionWorkflowExtension extension = extensions.get(index);
            if (!extension.supports(context)) continue;
            invoked = java.util.Objects.requireNonNull(
                extension.afterInvoke(context, effectiveCall, invoked),
                () -> extension.getClass().getName() + " returned null MCP result");
        }

        McpResultRepairResult repaired = null;
        if (invoked.rawData() != null && invoked.data() == null && repairable(invoked)) {
            repaired = directory.repair(new McpResultRepairRequest(null, invoked.requestId(),
                invoked.serviceId(), invoked.toolName(), invoked.rawData(), invoked.errorMessage(),
                Map.of(), effectiveCall.context()));
            if (repaired.normalizedData() != null) {
                invoked = new McpServiceResult(null, invoked.requestId(), invoked.serviceId(), invoked.toolName(),
                    repaired.status(), repaired.normalizedData(), invoked.rawData(), null,
                    null, false, null, invoked.metadata(), invoked.resultKind(), invoked.resultSchemaRef(),
                    invoked.provenance(), invoked.pagination(), 0);
            }
        }
        McpContractAuditReport postflight = contracts.audit(new McpContractAuditRequest(
            call.serviceId(), call.toolName(), analysis.binding() == null
                ? analysis.requestedTemplateId() : null,
            stringSet(call.context().get("requiredArguments")), invoked));
        return new Execution(effectiveCall, selectedService, selectedTool, invoked, preflight, postflight,
            repaired, refreshAttempted, List.copyOf(appliedExtensions));
    }

    @Override
    protected void verify(McpServiceCall input, Analysis analysis, Plan plan,
                          Execution execution, KernelDataScope scope) {
        if (execution == null || execution.result() == null) {
            throw new IllegalStateException("MCP execution workflow produced no result");
        }
        McpServiceResult result = execution.result();
        if (!input.requestId().equals(result.requestId())
            || !input.serviceId().equals(result.serviceId())
            || !input.toolName().equals(result.toolName())) {
            throw new IllegalStateException("MCP provider returned a result for a different invocation target");
        }
    }

    @Override
    protected McpServiceResult assemble(McpServiceCall call, Analysis analysis, Plan plan,
                                        Execution execution, KernelDataScope scope) {
        Map<String, Object> metadata = new LinkedHashMap<>(execution.result().metadata());
        if (execution.preflight() != null) metadata.put("preflightAudit", execution.preflight());
        if (execution.postflight() != null) metadata.put("postflightAudit", execution.postflight());
        if (execution.repair() != null) metadata.put("automaticRepair", execution.repair());
        metadata.put("kernelProtocolVersion", McpRuntimeKernel.KERNEL_PROTOCOL_VERSION);
        metadata.put("discoveryRefreshAttempted", execution.discoveryRefreshAttempted());
        metadata.put("templateBindingValidation", templateBindingMetadata(
            analysis.requestedTemplateId(), call.toolName(), analysis.binding()));
        metadata.put("mcpExecutionWorkflow", workflowTrace(analysis, plan, execution));
        McpServiceResult result = copyWithMetadata(execution.result(), metadata);
        log.debug("MCP execution workflow completed requestId={} serviceId={} tool={} route={} status={}",
            call.requestId(), call.serviceId(), call.toolName(), route(execution.service()), result.status());
        return result;
    }

    private McpServiceDescriptor discoverService(String serviceId) {
        return directory.services().stream()
            .filter(service -> service.serviceId().equals(serviceId))
            .findFirst().orElse(null);
    }

    private McpToolDescriptor discoverTool(McpServiceCall call) {
        return directory.tools(new McpToolQuery(call.serviceId(), null, Set.of(call.toolName())))
            .stream().filter(tool -> call.serviceId().equals(tool.serviceId()))
            .findFirst().orElse(null);
    }

    private McpServiceCall requireSameTarget(McpServiceCall original, McpServiceCall prepared,
                                             McpExecutionWorkflowExtension extension) {
        if (prepared == null) {
            throw new IllegalStateException(extension.getClass().getName() + " returned null MCP call");
        }
        if (!original.requestId().equals(prepared.requestId())
            || !original.serviceId().equals(prepared.serviceId())
            || !original.toolName().equals(prepared.toolName())) {
            throw new IllegalStateException(extension.getClass().getName()
                + " changed immutable MCP invocation identity");
        }
        return prepared;
    }

    private McpServiceResult contractRejection(McpServiceCall call, Analysis analysis,
                                                McpContractAuditReport preflight,
                                                boolean refreshAttempted) {
        List<String> findingCodes = preflight.findings().stream()
            .map(finding -> finding.code()).distinct().toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("preflightFindingCodes", findingCodes);
        metadata.put("discoveryRefreshAttempted", refreshAttempted);
        String message = findingCodes.isEmpty()
            ? "MCP invocation rejected by Runtime OS contract preflight"
            : "MCP invocation rejected by Runtime OS contract preflight: " + String.join(",", findingCodes);
        log.warn("MCP invocation stopped before provider transport: requestId={} serviceId={} tool={} "
                + "templateId={} findingCodes={}",
            call.requestId(), call.serviceId(), call.toolName(), analysis.requestedTemplateId(), findingCodes);
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            McpServiceResultStatus.REJECTED, null, null, "MCP_CONTRACT_PREFLIGHT_FAILED",
            message, false, "REPAIR_CONTRACT_OR_REDISCOVER", metadata, 0);
    }

    private McpServiceResult invalidTemplateBinding(McpServiceCall call, String templateId, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "templateId", templateId);
        metadata.put("executorTool", call.toolName());
        metadata.put(McpTemplateBindingEvidence.INVALID_REASON_KEY,
            reason == null ? "runtime template binding is invalid" : reason);
        metadata.put("failureStage", "MCP_KERNEL_PREFLIGHT");
        metadata.put("resourceFailureCategory", "CONTEXT_LOST");
        log.warn("MCP execution rejected because Runtime template binding is invalid: requestId={} "
                + "serviceId={} tool={} templateId={} reason={}",
            call.requestId(), call.serviceId(), call.toolName(), templateId, reason);
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            McpServiceResultStatus.REJECTED, null, null, "MCP_TEMPLATE_BINDING_INVALID",
            "Runtime template binding context is invalid: " + reason, false,
            "REBUILD_RUNTIME_TEMPLATE_BINDING", metadata, 0);
    }

    private McpServiceResult validateToolContractSnapshot(McpServiceCall call,
                                                          McpTemplateBindingEvidence binding,
                                                          McpToolDescriptor current) {
        if (binding == null || current == null
            || (binding.toolContractVersion() == null && binding.toolContractHash() == null)) return null;
        String currentVersion = firstText(current.metadata().get("workflowContractVersion"));
        String currentHash = firstText(current.metadata().get("workflowContractChecksum"));
        boolean versionChanged = binding.toolContractVersion() != null && currentVersion != null
            && !binding.toolContractVersion().equals(currentVersion);
        boolean contentChanged = binding.toolContractHash() != null && currentHash != null
            && !binding.toolContractHash().equals(currentHash);
        if (!versionChanged && !contentChanged) return null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failureStage", "MCP_KERNEL_PREFLIGHT");
        metadata.put("resourceFailureCategory", "VERSION_MISMATCH");
        metadata.put("templateId", binding.templateId());
        putIfPresent(metadata, "snapshottedToolContractVersion", binding.toolContractVersion());
        putIfPresent(metadata, "currentToolContractVersion", currentVersion);
        putIfPresent(metadata, "snapshottedToolContractHash", binding.toolContractHash());
        putIfPresent(metadata, "currentToolContractHash", currentHash);
        log.warn("MCP execution rejected because the tool contract changed: requestId={} serviceId={} "
                + "tool={} templateId={} versionChanged={} contentChanged={}",
            call.requestId(), call.serviceId(), call.toolName(), binding.templateId(),
            versionChanged, contentChanged);
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(),
            McpServiceResultStatus.REJECTED, null, null, "RESOURCE_VERSION_MISMATCH",
            "Executor tool contract changed after plan preflight", false,
            "REDISCOVER_TEMPLATE_AND_RECOMPILE_PLAN", metadata, 0);
    }

    private Map<String, Object> workflowTrace(Analysis analysis, Plan plan, Execution execution) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("workflowId", WORKFLOW_ID);
        trace.put("steps", plan.steps());
        trace.put("route", route(execution.service()));
        trace.put("toolDiscovered", execution.tool() != null);
        trace.put("discoveryRefreshAttempted", execution.discoveryRefreshAttempted());
        trace.put("repairApplied", execution.repair() != null);
        trace.put("extensions", execution.extensions());
        putIfPresent(trace, "providerId", execution.service() == null
            ? plan.providerId() : execution.service().providerId());
        putIfPresent(trace, "transport", execution.service() == null
            ? plan.transport() : execution.service().transport());
        if (execution.tool() != null) {
            trace.put("localToolName", execution.tool().localToolName());
            trace.put("remoteToolName", execution.tool().remoteToolName());
        }
        return Map.copyOf(trace);
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

    private McpServiceResult copyWithMetadata(McpServiceResult result, Map<String, Object> metadata) {
        return new McpServiceResult(null, result.requestId(), result.serviceId(), result.toolName(), result.status(),
            result.data(), result.rawData(), result.errorCode(), result.errorMessage(), result.retryable(),
            result.recoveryAction(), metadata, result.resultKind(), result.resultSchemaRef(), result.provenance(),
            result.pagination(), result.completedAt());
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

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        values.forEach(item -> { if (text(item) != null) result.add(text(item)); });
        return Set.copyOf(result);
    }

    private String firstText(Object... values) {
        for (Object value : values) if (text(value) != null) return text(value);
        return null;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String route(McpServiceDescriptor service) {
        return service != null && "in-process".equalsIgnoreCase(service.transport())
            ? "INTERNAL" : "PROVIDER";
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    public record Analysis(String requestedTemplateId, McpTemplateBindingEvidence binding,
                           McpServiceDescriptor service, McpToolDescriptor tool,
                           McpServiceResult rejection) { }

    public record Plan(List<String> steps, String providerId, String transport, String route) {
        public Plan { steps = List.copyOf(steps); }
    }

    public record Execution(McpServiceCall call, McpServiceDescriptor service,
                            McpToolDescriptor tool, McpServiceResult result,
                            McpContractAuditReport preflight, McpContractAuditReport postflight,
                            McpResultRepairResult repair, boolean discoveryRefreshAttempted,
                            List<String> extensions) {
        public Execution { extensions = List.copyOf(extensions); }
    }

    public record Context(KernelDataScope scope, McpServiceDescriptor service,
                          McpToolDescriptor tool, String route, String templateId) { }
}
