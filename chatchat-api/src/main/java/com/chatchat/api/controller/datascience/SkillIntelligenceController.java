package com.chatchat.api.controller.datascience;

import com.chatchat.api.runtime.SkillAnalysisRunService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.application.SkillIntelligenceLayer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Explicit analysis entry point. API analyses are persisted as runs, never sidebar conversations. */
@RestController
@RequestMapping(AppConstants.API_V1 + "/data-science/domain-skills/intelligence")
public class SkillIntelligenceController {
    private final SkillIntelligenceLayer intelligence;
    private final SkillAnalysisRunService runs;
    private final EnterpriseAdminService authorization;
    private final com.chatchat.chat.skills.catalog.SkillCatalogService agents;
    public SkillIntelligenceController(SkillIntelligenceLayer intelligence, SkillAnalysisRunService runs,
                                       EnterpriseAdminService authorization, com.chatchat.chat.skills.catalog.SkillCatalogService agents) {
        this.intelligence = intelligence; this.runs = runs; this.authorization = authorization; this.agents = agents;
    }
    @PostMapping("/plan") public ApiResponse<?> plan(@RequestBody Request body, HttpServletRequest http) {
        return ApiResponse.success(intelligence.plan(request(body, http)));
    }
    @PostMapping("/execute") public ApiResponse<?> execute(@RequestBody Request body, HttpServletRequest http) {
        var request = request(body, http);
        var result = intelligence.execute(request);
        return ApiResponse.success(Map.of("runId", runs.save(request.identity(), request, result), "result", result));
    }
    private SkillCompositionRequest request(Request body, HttpServletRequest http) {
        if (!(http.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW) instanceof EnterpriseAdminService.UserView user)
            || user.id() == null || user.tenantId() == null || !"enabled".equalsIgnoreCase(user.status()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user required");
        if (body.agentId() == null || body.agentId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId required for scoped skill authorization");
        if (!authorization.canAccessAgent(user.id(), body.agentId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent is not authorized");
        String engine = body.engine() == null || body.engine().isBlank() ? "GOOGLE_ADK_NATIVE" : body.engine().toUpperCase(Locale.ROOT);
        if (!Set.of("GOOGLE_ADK_NATIVE", "LANGCHAIN4J", "OPENAI_COMPATIBLE").contains(engine))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analysis engine is not registered");
        String model = agents.resolve(body.agentId()).modelName();
        if (model == null || model.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configure the invoking Agent model first");
        var inputs = body.inputs() == null ? Map.<String, Object>of() : body.inputs();
        if (inputs.size() > 32 || inputs.entrySet().stream().anyMatch(entry -> entry.getKey().length() > 100
            || !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)
            || entry.getValue().toString().length() > 1000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply bounded scalar inputs");
        try {
            return new SkillCompositionRequest(body.query(), new SkillRoleContext(user.tenantId(), user.id(), user.roleIds(),
                List.of(), Map.of("agentId", body.agentId())), body.capabilities(), body.skillIds(), body.workflowIds(), inputs,
                engine, Map.of("modelName", model, "maxSteps", 6, "maxToolCalls", 0, "timeoutMs", 60000L),
                body.maxSkills() == null ? 4 : body.maxSkills());
        } catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
    }
    public record Request(String query, String agentId, String engine, String modelName, List<String> capabilities,
                          List<String> skillIds, Map<String, String> workflowIds, Map<String, Object> inputs, Integer maxSkills) {}
}
