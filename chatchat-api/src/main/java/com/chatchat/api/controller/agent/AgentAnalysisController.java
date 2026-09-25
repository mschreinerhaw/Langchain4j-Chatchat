package com.chatchat.api.controller.agent;

import com.chatchat.api.config.RequestCorrelationFilter;
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
}
