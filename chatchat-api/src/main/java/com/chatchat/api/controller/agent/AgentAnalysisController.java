package com.chatchat.api.controller.agent;

import com.chatchat.api.config.RequestCorrelationFilter;
import com.chatchat.api.runtime.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.PreauthorizedStructuredDataOperator;
import com.chatchat.api.runtime.VerifiedEvidenceComputationOperator;
import com.chatchat.api.runtime.GovernedExternalResearchOperator;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.common.runtime.agent.AgentCollaborationPlan;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authenticated vertical entry into Query -> Planner -> Agent -> Evidence -> Judge. */
@RestController
@RequestMapping("/api/v1/agent/analysis")
@Tag(name = "Agent Runtime OS Analysis")
public class AgentAnalysisController {
    private final AnalysisRuntimePort analysis;
    private final SkillExecutionScopePort skillScopes;
    private final AgentRegistryPort agentRegistry;

    public AgentAnalysisController(AnalysisRuntimePort analysis, SkillExecutionScopePort skillScopes) {
        this(analysis, skillScopes, null);
    }

    @Autowired
    public AgentAnalysisController(AnalysisRuntimePort analysis, SkillExecutionScopePort skillScopes,
                                   AgentRegistryPort agentRegistry) {
        this.analysis = analysis;
        this.skillScopes = skillScopes;
        this.agentRegistry = agentRegistry;
    }

    @GetMapping("/domain-providers")
    @Operation(summary = "List tenant-admitted domain intelligence compute providers without secrets")
    public ApiResponse<List<DomainProviderOption>> domainProviders(HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant is required");
        return ApiResponse.success(agentRegistry.list().stream()
            .filter(agent -> agent.enabled() && agent.origin() != AgentDescriptor.Origin.LOCAL
                && agent.supportsExecutionMode(AgentExecutionMode.DOMAIN_INFERENCE)
                && providerAllowsTenant(agent, tenantId))
            .map(agent -> new DomainProviderOption(agent.agentId(),
                String.valueOf(agent.metadata().getOrDefault("displayName", agent.agentId())),
                agent.origin().name(),
                agent.capabilities().stream().map(CapabilityId::value).sorted().toList(),
                agent.allowedDataDomains().stream().sorted().toList(),
                agent.allowedEvidenceTypes().stream().sorted().toList()))
            .toList());
    }

    @PostMapping("/domain-resources")
    @Operation(summary = "Resolve documents available to the caller through one selected Knowledge Skill")
    public ApiResponse<List<String>> domainResources(@RequestBody DomainSkillResourcesRequest body,
                                                      HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.skillId() == null || body.skillId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillId is required");
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), bounded(body.documentIds()),
            bounded(body.documentTags()));
        if (!scope.skillAllowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Knowledge Skill is not authorized");
        return ApiResponse.success(scope.documentIds().stream()
            .filter(id -> !SkillExecutionScopePort.DENIED_DOCUMENT_ID.equals(id)).sorted().toList());
    }

    @PostMapping("/domain-intelligence")
    @Operation(summary = "Run authorized Skill and read-only MCP evidence before a selected domain provider")
    public ApiResponse<AnalysisExecutionOutcome> analyzeDomain(@RequestBody DomainAnalyzeRequest body,
                                                                 HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 4000
            || body.skillId() == null || body.skillId().isBlank()
            || body.providerId() == null || body.providerId().isBlank()
            || body.capability() == null || body.capability().isBlank()
            || !body.confirmRemoteTransfer())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "query, skillId, providerId, capability and remote evidence confirmation are required");
        CapabilityId capability;
        try { capability = CapabilityId.parse(body.capability()); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid capability", invalid);
        }
        AgentDescriptor provider = agentRegistry.find(body.providerId()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Domain provider is unavailable"));
        if (!provider.enabled() || provider.origin() == AgentDescriptor.Origin.LOCAL
            || !provider.capabilities().contains(capability)
            || !provider.supportsExecutionMode(AgentExecutionMode.DOMAIN_INFERENCE)
            || !providerAllowsTenant(provider, tenantId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Domain provider is not admitted for this request");
        List<String> requestedDocuments = bounded(body.documentIds());
        if (body.documentTags() != null && !body.documentTags().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Select explicit document IDs for domain evidence transfer");
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), requestedDocuments, List.of());
        if (!scope.skillAllowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Skill is not authorized");
        if (!requestedDocuments.isEmpty() && !scope.documentIds().containsAll(requestedDocuments))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "One or more selected documents are not authorized");
        List<DomainToolCall> tools = body.tools() == null ? List.of() : body.tools();
        if (tools.size() > 4 || tools.stream().anyMatch(tool -> tool == null || tool.toolName() == null
            || tool.toolName().isBlank() || tool.arguments() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at most four named MCP tools with arguments");
        boolean structured = body.dataTemplateId() != null && !body.dataTemplateId().isBlank();
        if (structured && (body.dataAssetName() == null || body.dataAssetName().isBlank()
            || body.dataEnvironment() == null || body.dataEnvironment().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Published data template requires an asset and environment");
        EnumSet<AnalysisCapability> required = EnumSet.of(AnalysisCapability.DOMAIN_INTELLIGENCE);
        if (!requestedDocuments.isEmpty()) required.add(AnalysisCapability.DOCUMENT_SEARCH);
        if (!tools.isEmpty()) required.add(AnalysisCapability.TOOL_CALL);
        if (structured) required.add(AnalysisCapability.STRUCTURED_DATA);
        if ((required.contains(AnalysisCapability.DOCUMENT_SEARCH)
            && !provider.allowedEvidenceTypes().contains("DocumentAnalysisEvidence"))
            || (required.contains(AnalysisCapability.TOOL_CALL)
                && !provider.allowedEvidenceTypes().contains("ToolAnalysisEvidence"))
            || (structured && !provider.allowedEvidenceTypes().contains("StructuredDataEvidence")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Selected provider is not allowed to receive one or more selected evidence types");
        if (required.size() == 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select authorized document or data evidence");
        String requestId = attribute(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) requestId = UUID.randomUUID().toString();
        KernelDataScope kernel = new KernelDataScope(tenantId, userId, requestId, null, requestId, null, Map.of());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE, provider.agentId());
        attributes.put(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE, capability.value());
        attributes.put(AnalysisContext.AGENT_EXECUTION_MODE_ATTRIBUTE, AgentExecutionMode.DOMAIN_INFERENCE.name());
        attributes.put("agentMaxAttempts", Math.max(1, Math.min(3,
            body.maxAttempts() == null ? 2 : body.maxAttempts())));
        attributes.put("agentTimeoutMs", Math.max(1000, Math.min(120_000,
            body.timeoutMs() == null ? 60_000 : body.timeoutMs())));
        if (!tools.isEmpty()) attributes.put(RegisteredToolAnalysisOperator.TOOL_CALLS, tools.stream()
            .map(tool -> Map.of("toolName", tool.toolName(), "arguments", tool.arguments())).toList());
        if (structured) {
            attributes.put(PreauthorizedStructuredDataOperator.TEMPLATE_ID, body.dataTemplateId());
            attributes.put(PreauthorizedStructuredDataOperator.ASSET_NAME, body.dataAssetName());
            attributes.put(PreauthorizedStructuredDataOperator.ENVIRONMENT, body.dataEnvironment());
            attributes.put(PreauthorizedStructuredDataOperator.PARAMETERS,
                body.dataParameters() == null ? Map.of() : body.dataParameters());
        }
        AnalysisIntent intent = new AnalysisIntent("DOMAIN_INTELLIGENCE_ANALYSIS", List.of(), required,
            "UNSPECIFIED", true);
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(), requestedDocuments,
            List.of(), scope.roles(), intent, attributes);
        return ApiResponse.success(analysis.analyze(context));
    }

    private boolean providerAllowsTenant(AgentDescriptor provider, String tenantId) {
        Object allowed = provider.metadata().get("allowedTenantIds");
        if (!(allowed instanceof Iterable<?> values)) return false;
        for (Object value : values) if (tenantId.equals(value)) return true;
        return false;
    }

    @PostMapping
    @Operation(summary = "Execute a policy-scoped domain Agent analysis with Runtime-owned evidence and judging")
    public ApiResponse<AnalysisExecutionOutcome> analyze(@RequestBody AnalyzeRequest body,
                                                         HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.query() == null || body.query().isBlank()
            || body.query().length() > 4000 || body.skillId() == null || body.skillId().isBlank()
            || body.capability() == null || body.capability().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query, skillId and capability are required");
        CapabilityId capability;
        try { capability = CapabilityId.parse(body.capability()); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid capability", invalid);
        }
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), bounded(body.documentIds()),
            bounded(body.documentTags()));
        if (!scope.skillAllowed() || scope.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Skill or document scope is not authorized");
        String requestId = attribute(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) requestId = UUID.randomUUID().toString();
        KernelDataScope kernel = new KernelDataScope(tenantId, userId, requestId, null, requestId,
            null, Map.of());
        int attempts = Math.max(1, Math.min(3, body.maxAttempts() == null ? 2 : body.maxAttempts()));
        long timeout = Math.max(1000, Math.min(120_000, body.timeoutMs() == null ? 60_000 : body.timeoutMs()));
        if (body.dataTemplateId() != null && !body.dataTemplateId().isBlank()
            && (body.dataAssetName() == null || body.dataAssetName().isBlank()
                || body.dataEnvironment() == null || body.dataEnvironment().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "dataAssetName and dataEnvironment are required with dataTemplateId");
        AnalysisIntent intent = new AnalysisIntent("DOMAIN_INTELLIGENCE_ANALYSIS", List.of(),
            Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE), "UNSPECIFIED", true);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE, capability.value());
        try {
            attributes.put(AnalysisContext.AGENT_EXECUTION_MODE_ATTRIBUTE,
                AgentExecutionMode.parse(body.agentExecutionMode()).name());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        attributes.put("agentMaxAttempts", attempts);
        attributes.put("agentTimeoutMs", timeout);
        if (body.dataTemplateId() != null && !body.dataTemplateId().isBlank()) {
            attributes.put(PreauthorizedStructuredDataOperator.TEMPLATE_ID, body.dataTemplateId());
            attributes.put(PreauthorizedStructuredDataOperator.ASSET_NAME, body.dataAssetName());
            attributes.put(PreauthorizedStructuredDataOperator.ENVIRONMENT, body.dataEnvironment());
            attributes.put(PreauthorizedStructuredDataOperator.PARAMETERS,
                body.dataParameters() == null ? Map.of() : body.dataParameters());
        }
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(),
            scope.documentIds(), scope.tags(), scope.roles(), intent,
            attributes);
        return ApiResponse.success(analysis.analyze(context));
    }

    @PostMapping("/collaborate")
    @Operation(summary = "Execute a bounded multi-Agent DAG through the existing federated analysis workflow")
    public ApiResponse<AnalysisExecutionOutcome> collaborate(@RequestBody CollaborateRequest body,
                                                               HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 4000
            || body.skillId() == null || body.skillId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query and skillId are required");
        AgentCollaborationPlan collaboration;
        try { collaboration = AgentCollaborationPlan.from(Map.of("tasks",
            body.tasks() == null ? List.of() : body.tasks())); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), bounded(body.documentIds()),
            bounded(body.documentTags()));
        if (!scope.skillAllowed() || scope.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Skill or document scope is not authorized");
        String requestId = attribute(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) requestId = UUID.randomUUID().toString();
        KernelDataScope kernel = new KernelDataScope(tenantId, userId, requestId, null, requestId, null, Map.of());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(AgentCollaborationPlan.CONTEXT_ATTRIBUTE, collaboration);
        attributes.put("agentMaxAttempts", Math.max(1, Math.min(3,
            body.maxAttempts() == null ? 2 : body.maxAttempts())));
        attributes.put("agentTimeoutMs", Math.max(1000, Math.min(120_000,
            body.timeoutMs() == null ? 60_000 : body.timeoutMs())));
        if (body.dataTemplateId() != null && !body.dataTemplateId().isBlank()) {
            if (body.dataAssetName() == null || body.dataAssetName().isBlank()
                || body.dataEnvironment() == null || body.dataEnvironment().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataAssetName and dataEnvironment are required with dataTemplateId");
            attributes.put(PreauthorizedStructuredDataOperator.TEMPLATE_ID, body.dataTemplateId());
            attributes.put(PreauthorizedStructuredDataOperator.ASSET_NAME, body.dataAssetName());
            attributes.put(PreauthorizedStructuredDataOperator.ENVIRONMENT, body.dataEnvironment());
            attributes.put(PreauthorizedStructuredDataOperator.PARAMETERS,
                body.dataParameters() == null ? Map.of() : body.dataParameters());
        }
        AnalysisIntent intent = new AnalysisIntent("MULTI_AGENT_ANALYSIS", List.of(),
            Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE), "UNSPECIFIED", true);
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(),
            scope.documentIds(), scope.tags(), scope.roles(), intent, attributes);
        return ApiResponse.success(analysis.analyze(context));
    }

    @PostMapping("/tool")
    @Operation(summary = "Run an explicitly Skill-bound read-only tool through governed Runtime and evidence judging")
    public ApiResponse<AnalysisExecutionOutcome> analyzeTool(@RequestBody ToolAnalyzeRequest body,
                                                             HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 4000
            || body.skillId() == null || body.skillId().isBlank()
            || body.toolName() == null || body.toolName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query, skillId and toolName are required");
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), List.of(), List.of());
        if (!scope.skillAllowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Skill is not authorized");
        String requestId = attribute(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) requestId = UUID.randomUUID().toString();
        KernelDataScope kernel = new KernelDataScope(tenantId, userId, requestId, null, requestId,
            null, Map.of());
        AnalysisIntent intent = new AnalysisIntent("GOVERNED_TOOL_ANALYSIS", List.of(),
            Set.of(AnalysisCapability.TOOL_CALL), "UNSPECIFIED", true);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(RegisteredToolAnalysisOperator.TOOL_NAME, body.toolName());
        attributes.put(RegisteredToolAnalysisOperator.TOOL_ARGUMENTS,
            body.arguments() == null ? Map.of() : body.arguments());
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(),
            List.of(), List.of(), scope.roles(), intent, attributes);
        return ApiResponse.success(analysis.analyze(context));
    }

    @PostMapping("/composite")
    @Operation(summary = "Verify authorized document/tool evidence before federated Agent analysis")
    public ApiResponse<AnalysisExecutionOutcome> analyzeComposite(@RequestBody CompositeAnalyzeRequest body,
                                                                  HttpServletRequest request) {
        String tenantId = attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (tenantId == null || userId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated tenant and user are required");
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 4000
            || body.skillId() == null || body.skillId().isBlank()
            || body.capability() == null || body.capability().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query, skillId and capability are required");
        CapabilityId capability;
        try { capability = CapabilityId.parse(body.capability()); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid capability", invalid);
        }
        var scope = skillScopes.resolve(tenantId, userId, body.skillId(), bounded(body.documentIds()),
            bounded(body.documentTags()));
        if (!scope.skillAllowed() || scope.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local Skill or document scope is not authorized");
        String requestId = attribute(request, RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) requestId = UUID.randomUUID().toString();
        KernelDataScope kernel = new KernelDataScope(tenantId, userId, requestId, null, requestId,
            null, Map.of());
        EnumSet<AnalysisCapability> required = EnumSet.of(AnalysisCapability.DOMAIN_INTELLIGENCE);
        if (!scope.documentIds().isEmpty() || !scope.tags().isEmpty())
            required.add(AnalysisCapability.DOCUMENT_SEARCH);
        if (body.toolName() != null && !body.toolName().isBlank())
            required.add(AnalysisCapability.TOOL_CALL);
        if (body.researchToolName() != null && !body.researchToolName().isBlank()) {
            if (body.researchTerms() == null || body.researchTerms().isEmpty()
                || body.researchTerms().size() > 8
                || body.researchTerms().stream().anyMatch(term -> term == null || term.isBlank()
                    || term.length() > 80 || term.contains("\n") || term.contains("\r")
                    || term.trim().equalsIgnoreCase(body.query().trim())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Explicit bounded researchTerms are required for external search");
            required.add(AnalysisCapability.EXTERNAL_RESEARCH);
        }
        if (body.dataTemplateId() != null && !body.dataTemplateId().isBlank()) {
            if (body.dataAssetName() == null || body.dataAssetName().isBlank()
                || body.dataEnvironment() == null || body.dataEnvironment().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataAssetName and dataEnvironment are required with dataTemplateId");
            required.add(AnalysisCapability.STRUCTURED_DATA);
        }
        if (body.metricOperation() != null && !body.metricOperation().isBlank()) {
            if (!required.contains(AnalysisCapability.STRUCTURED_DATA))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deterministic metric requires structured data evidence");
            required.add(AnalysisCapability.COMPUTATION);
        }
        if (required.size() == 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one document or tool evidence source is required");
        int attempts = Math.max(1, Math.min(3, body.maxAttempts() == null ? 2 : body.maxAttempts()));
        long timeout = Math.max(1000, Math.min(120_000, body.timeoutMs() == null ? 60_000 : body.timeoutMs()));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE, capability.value());
        try {
            attributes.put(AnalysisContext.AGENT_EXECUTION_MODE_ATTRIBUTE,
                AgentExecutionMode.parse(body.agentExecutionMode()).name());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        attributes.put("agentMaxAttempts", attempts);
        attributes.put("agentTimeoutMs", timeout);
        if (required.contains(AnalysisCapability.TOOL_CALL)) {
            attributes.put(RegisteredToolAnalysisOperator.TOOL_NAME, body.toolName());
            attributes.put(RegisteredToolAnalysisOperator.TOOL_ARGUMENTS,
                body.arguments() == null ? Map.of() : body.arguments());
        }
        if (required.contains(AnalysisCapability.EXTERNAL_RESEARCH)) {
            attributes.put(GovernedExternalResearchOperator.TOOL_NAME, body.researchToolName());
            attributes.put(GovernedExternalResearchOperator.SEARCH_TERMS,
                body.researchTerms().stream().map(String::trim).distinct().toList());
        }
        if (required.contains(AnalysisCapability.STRUCTURED_DATA)) {
            attributes.put(PreauthorizedStructuredDataOperator.TEMPLATE_ID, body.dataTemplateId());
            attributes.put(PreauthorizedStructuredDataOperator.ASSET_NAME, body.dataAssetName());
            attributes.put(PreauthorizedStructuredDataOperator.ENVIRONMENT, body.dataEnvironment());
            attributes.put(PreauthorizedStructuredDataOperator.PARAMETERS,
                body.dataParameters() == null ? Map.of() : body.dataParameters());
        }
        if (required.contains(AnalysisCapability.COMPUTATION)) {
            attributes.put(VerifiedEvidenceComputationOperator.OPERATION, body.metricOperation());
            attributes.put(VerifiedEvidenceComputationOperator.FIELD,
                body.metricField() == null ? "" : body.metricField());
        }
        AnalysisIntent intent = new AnalysisIntent("COMPOSITE_DOMAIN_ANALYSIS", List.of(), required,
            required.contains(AnalysisCapability.EXTERNAL_RESEARCH) ? "CURRENT" : "UNSPECIFIED", true);
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(),
            scope.documentIds(), scope.tags(), scope.roles(), intent, attributes);
        return ApiResponse.success(analysis.analyze(context));
    }

    private List<String> bounded(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many scope selectors");
        return values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    public record AnalyzeRequest(String query, String skillId, String capability,
                                 List<String> documentIds, List<String> documentTags,
                                 Integer maxAttempts, Long timeoutMs,
                                 String dataTemplateId, String dataAssetName,
                                 String dataEnvironment, Map<String, Object> dataParameters,
                                 String agentExecutionMode) {
        public AnalyzeRequest(String query, String skillId, String capability,
                              List<String> documentIds, List<String> documentTags,
                              Integer maxAttempts, Long timeoutMs,
                              String dataTemplateId, String dataAssetName,
                              String dataEnvironment, Map<String, Object> dataParameters) {
            this(query, skillId, capability, documentIds, documentTags, maxAttempts, timeoutMs,
                dataTemplateId, dataAssetName, dataEnvironment, dataParameters, null);
        }
        public AnalyzeRequest(String query, String skillId, String capability,
                              List<String> documentIds, List<String> documentTags,
                              Integer maxAttempts, Long timeoutMs) {
            this(query, skillId, capability, documentIds, documentTags, maxAttempts, timeoutMs,
                null, null, null, null, null);
        }
    }

    public record CollaborateRequest(String query, String skillId,
                                     List<String> documentIds, List<String> documentTags,
                                     List<Map<String, Object>> tasks,
                                     Integer maxAttempts, Long timeoutMs,
                                     String dataTemplateId, String dataAssetName,
                                     String dataEnvironment, Map<String, Object> dataParameters) { }

    public record ToolAnalyzeRequest(String query, String skillId, String toolName,
                                     Map<String, Object> arguments) { }

    public record DomainProviderOption(String providerId, String displayName, String origin, List<String> capabilities,
                                       List<String> dataDomains, List<String> evidenceTypes) { }

    public record DomainSkillResourcesRequest(String skillId, List<String> documentIds,
                                              List<String> documentTags) { }

    public record DomainToolCall(String toolName, Map<String, Object> arguments) { }

    public record DomainAnalyzeRequest(String query, String skillId, String providerId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       List<DomainToolCall> tools, String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters,
                                       Integer maxAttempts, Long timeoutMs, boolean confirmRemoteTransfer) { }

    public record CompositeAnalyzeRequest(String query, String skillId, String capability,
                                          List<String> documentIds, List<String> documentTags,
                                          String toolName, Map<String, Object> arguments,
                                          Integer maxAttempts, Long timeoutMs,
                                          String dataTemplateId, String dataAssetName,
                                          String dataEnvironment, Map<String, Object> dataParameters,
                                          String metricOperation, String metricField,
                                          String researchToolName, List<String> researchTerms,
                                          String agentExecutionMode) {
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs,
                                       String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters,
                                       String metricOperation, String metricField,
                                       String researchToolName, List<String> researchTerms) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, dataTemplateId, dataAssetName, dataEnvironment, dataParameters,
                metricOperation, metricField, researchToolName, researchTerms, null);
        }
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, null, null, null, null, null, null, null, null, null);
        }
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs,
                                       String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, dataTemplateId, dataAssetName, dataEnvironment, dataParameters,
                null, null, null, null);
        }
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs,
                                       String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters,
                                       String metricOperation, String metricField) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, dataTemplateId, dataAssetName, dataEnvironment, dataParameters,
                metricOperation, metricField, null, null);
        }
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs,
                                       String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters,
                                       String metricOperation, String metricField,
                                       String researchToolName) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, dataTemplateId, dataAssetName, dataEnvironment, dataParameters,
                metricOperation, metricField, researchToolName, null);
        }
    }
}
