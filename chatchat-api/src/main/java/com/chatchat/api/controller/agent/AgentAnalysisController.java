package com.chatchat.api.controller.agent;

import com.chatchat.api.config.RequestCorrelationFilter;
import com.chatchat.api.runtime.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.PreauthorizedStructuredDataOperator;
import com.chatchat.api.runtime.VerifiedEvidenceComputationOperator;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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

    public AgentAnalysisController(AnalysisRuntimePort analysis, SkillExecutionScopePort skillScopes) {
        this.analysis = analysis;
        this.skillScopes = skillScopes;
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
        AnalysisIntent intent = new AnalysisIntent("DOMAIN_INTELLIGENCE_ANALYSIS", List.of(),
            Set.of(AnalysisCapability.DOMAIN_INTELLIGENCE), "UNSPECIFIED", true);
        AnalysisContext context = new AnalysisContext(body.query(), kernel, body.skillId(),
            scope.documentIds(), scope.tags(), scope.roles(), intent,
            Map.of(AnalysisContext.AGENT_CAPABILITY_ATTRIBUTE, capability.value(),
                "agentMaxAttempts", attempts, "agentTimeoutMs", timeout));
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
        attributes.put("agentMaxAttempts", attempts);
        attributes.put("agentTimeoutMs", timeout);
        if (required.contains(AnalysisCapability.TOOL_CALL)) {
            attributes.put(RegisteredToolAnalysisOperator.TOOL_NAME, body.toolName());
            attributes.put(RegisteredToolAnalysisOperator.TOOL_ARGUMENTS,
                body.arguments() == null ? Map.of() : body.arguments());
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
            "UNSPECIFIED", true);
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
                                 Integer maxAttempts, Long timeoutMs) { }

    public record ToolAnalyzeRequest(String query, String skillId, String toolName,
                                     Map<String, Object> arguments) { }

    public record CompositeAnalyzeRequest(String query, String skillId, String capability,
                                          List<String> documentIds, List<String> documentTags,
                                          String toolName, Map<String, Object> arguments,
                                          Integer maxAttempts, Long timeoutMs,
                                          String dataTemplateId, String dataAssetName,
                                          String dataEnvironment, Map<String, Object> dataParameters,
                                          String metricOperation, String metricField) {
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, null, null, null, null, null, null);
        }
        public CompositeAnalyzeRequest(String query, String skillId, String capability,
                                       List<String> documentIds, List<String> documentTags,
                                       String toolName, Map<String, Object> arguments,
                                       Integer maxAttempts, Long timeoutMs,
                                       String dataTemplateId, String dataAssetName,
                                       String dataEnvironment, Map<String, Object> dataParameters) {
            this(query, skillId, capability, documentIds, documentTags, toolName, arguments,
                maxAttempts, timeoutMs, dataTemplateId, dataAssetName, dataEnvironment, dataParameters,
                null, null);
        }
    }
}
