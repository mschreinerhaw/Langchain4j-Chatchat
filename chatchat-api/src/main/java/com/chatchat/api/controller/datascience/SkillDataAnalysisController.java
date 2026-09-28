package com.chatchat.api.controller.datascience;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.api.execution.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.execution.SkillExecutionResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.application.SkillDataAcquisition;
import com.chatchat.runtime.skill.port.inbound.SkillRuntime;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

/** Explicit first-stage entry point; the request supplies business inputs, never tool bindings. */
@RestController
@RequestMapping(AppConstants.API_V1 + "/data-science/domain-skills")
public class SkillDataAnalysisController {
    private final SkillRuntime runtime;
    public SkillDataAnalysisController(SkillRuntime runtime) { this.runtime = runtime; }

    @PostMapping("/{skillId}/analyze")
    public ApiResponse<SkillExecutionResult> analyze(@PathVariable("skillId") String skillId,
            @RequestBody AnalysisRequest body, HttpServletRequest request) {
        if (!(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW) instanceof EnterpriseAdminService.UserView user)
            || user.id() == null || user.tenantId() == null || !"enabled".equalsIgnoreCase(user.status()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
        if (body == null || body.query() == null || body.query().isBlank() || body.query().length() > 4000
            || body.workflowId() == null || body.workflowId().isBlank() || skillId.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query, workflowId and skillId are required");
        Map<String, Object> inputs = body.inputs() == null ? Map.of() : body.inputs();
        if (inputs.size() > 32 || inputs.entrySet().stream().anyMatch(entry -> entry.getKey().length() > 100
            || !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)
            || entry.getValue().toString().length() > 1000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply at most 32 bounded scalar business inputs");
        var identity = new SkillRoleContext(user.tenantId(), user.id(), user.roleIds(), List.of(), Map.of());
        return ApiResponse.success(runtime.execute(new SkillExecutionRequest(body.query(), identity,
            List.of(skillId), 1, "LANGCHAIN4J", Map.of("workflowId", body.workflowId(), "workflowType", "DATA_ANALYSIS",
                "dataContractsRequired", true),
            Map.of(SkillDataAcquisition.INPUTS, inputs, "modelName", body.modelName() == null ? "" : body.modelName(),
                "maxSteps", 4, "maxToolCalls", 1, "timeoutMs", 60000L))));
    }

    public record AnalysisRequest(String query, String workflowId, String modelName, Map<String, Object> inputs) { }
}
