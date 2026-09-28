package com.chatchat.api.controller.datascience;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.runtime.*;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.application.SkillCompositionRuntime;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping(AppConstants.API_V1 + "/data-science/domain-skills")
public class SkillCompositionController {
    private final SkillCompositionRuntime runtime;
    private final SkillAnalysisRunService runs;
    private final SkillDataBindingService bindings;
    public SkillCompositionController(SkillCompositionRuntime runtime,SkillAnalysisRunService runs,SkillDataBindingService bindings) {
        this.runtime=runtime;this.runs=runs;this.bindings=bindings;
    }
    @PostMapping("/analysis-plan")
    public ApiResponse<?> plan(@RequestBody Request body,HttpServletRequest http) {
        return ApiResponse.success(runtime.plan(request(body,user(http))));
    }
    @PostMapping("/analyze-capabilities")
    public ApiResponse<?> analyze(@RequestBody Request body,HttpServletRequest http) {
        var request=request(body,user(http));var result=runtime.execute(request);
        return ApiResponse.success(Map.of("runId",runs.save(request.identity(),request,result),"result",result));
    }
    @PostMapping("/experiments")
    public ApiResponse<?> experiment(@RequestBody Request body,HttpServletRequest http) {
        var request=request(body,user(http));var plan=runtime.plan(request);var session=new SkillDataSession();
        var enhanced=runtime.execute(request,session,plan);
        var attributes=new LinkedHashMap<>(request.attributes());attributes.put("analysisBaseline",true);
        var baselineRequest=new SkillCompositionRequest(request.query(),request.identity(),request.capabilities(),request.skillIds(),
            request.workflowIds(),request.inputs(),request.engine(),attributes,request.maxSkills());
        var baseline=runtime.execute(baselineRequest,session,plan);
        var enhancedEvidence=evidenceIds(enhanced);var baselineEvidence=evidenceIds(baseline);
        var result=Map.of("enhanced",enhanced,"baseline",baseline,
            "comparison",Map.of("sameAcquiredEvidence",!enhancedEvidence.isEmpty() && enhancedEvidence.equals(baselineEvidence),
                "enhancedMetrics",enhanced.metrics(),"baselineMetrics",baseline.metrics(),
                "qualityAssessment","HUMAN_REVIEW_REQUIRED"));
        return ApiResponse.success(Map.of("runId",runs.save(request.identity(),request,result),"result",result));
    }
    @GetMapping("/analysis-runs/{id}")
    public ApiResponse<?> run(@PathVariable("id") String id,HttpServletRequest http) {
        return ApiResponse.success(runs.read(id,identity(user(http))));
    }
    @PostMapping("/analysis-runs/{id}/review")
    public ApiResponse<?> review(@PathVariable("id") String id,@RequestBody Review body,HttpServletRequest http) {
        return ApiResponse.success(runs.review(id,identity(user(http)),body.status(),body.notes(),body.revision()));
    }
    @GetMapping("/{skillId}/data-bindings")
    public ApiResponse<?> bindings(@PathVariable("skillId") String skill,HttpServletRequest http) {
        var user=admin(http);return ApiResponse.success(bindings.list(user.tenantId(),skill));
    }
    @PutMapping("/{skillId}/data-bindings")
    public ApiResponse<?> draft(@PathVariable("skillId") String skill,@RequestBody BindingDraft body,HttpServletRequest http) {
        var user=admin(http);
        if(body.binding()==null || !skill.equals(body.binding().domainSkillId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Binding Skill must match route");
        return ApiResponse.success(bindings.draft(user.tenantId(),body.binding(),body.revision()));
    }
    @PostMapping("/{skillId}/data-bindings/{contractId}/publish")
    public ApiResponse<?> publish(@PathVariable("skillId") String skill,@PathVariable("contractId") String contract,
            @RequestBody Revision body,HttpServletRequest http) {
        var user=admin(http);return ApiResponse.success(bindings.publish(user.tenantId(),skill,contract,body.revision(),user.id()));
    }
    @PostMapping("/{skillId}/data-bindings/{contractId}/retire")
    public ApiResponse<?> retire(@PathVariable("skillId") String skill,@PathVariable("contractId") String contract,
            @RequestBody Revision body,HttpServletRequest http) {
        var user=admin(http);bindings.retire(user.tenantId(),skill,contract,body.revision());return ApiResponse.success(true);
    }
    private EnterpriseAdminService.UserView user(HttpServletRequest http) {
        if(!(http.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW) instanceof EnterpriseAdminService.UserView user)
            || user.id()==null || user.tenantId()==null || !"enabled".equalsIgnoreCase(user.status()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authenticated user is required");
        return user;
    }
    private EnterpriseAdminService.UserView admin(HttpServletRequest http) {
        var user=user(http);
        if(!"admin".equalsIgnoreCase(user.username())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only admin can publish data bindings");
        return user;
    }
    private SkillRoleContext identity(EnterpriseAdminService.UserView user) {
        return new SkillRoleContext(user.tenantId(),user.id(),user.roleIds(),List.of(),Map.of());
    }
    private SkillCompositionRequest request(Request body,EnterpriseAdminService.UserView user) {
        var inputs=body.inputs()==null ? Map.<String,Object>of() : body.inputs();
        if(inputs.size()>32 || inputs.entrySet().stream().anyMatch(entry -> entry.getKey()==null || entry.getKey().length()>100
            || !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)
            || entry.getValue().toString().length()>1000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supply bounded scalar business inputs");
        try {
            return new SkillCompositionRequest(body.query(),identity(user),body.capabilities(),body.skillIds(),body.workflowIds(),
                inputs,"LANGCHAIN4J",Map.of("modelName",body.modelName()==null ? "" : body.modelName(),
                "maxSteps",4,"maxToolCalls",0,"timeoutMs",60000L),body.maxSkills()==null ? 4 : body.maxSkills());
        } catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage());}
    }
    private Map<String,List<String>> evidenceIds(SkillCompositionResult result) {
        Map<String,List<String>> ids=new LinkedHashMap<>();
        result.results().forEach((skill,execution) -> {
            if(execution.diagnostics().get("skillDataResults") instanceof List<?> rows)
                for(Object row:rows) if(row instanceof SkillDataResult data && data.provenance().get("evidenceId") instanceof String id)
                    ids.put(skill + "/" + data.requirement().id(),List.of(id));
        });
        return ids;
    }
    public record Request(String query,List<String> capabilities,List<String> skillIds,Map<String,String> workflowIds,
        Map<String,Object> inputs,String modelName,Integer maxSkills) {}
    public record Review(String status,String notes,long revision) {}
    public record BindingDraft(SkillDataWorkflowProperties.Binding binding,Long revision) {}
    public record Revision(long revision) {}

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> status(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
            .body(ApiResponse.error(error.getStatusCode().value(),error.getReason()));
    }
    @ExceptionHandler({IllegalStateException.class,org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public org.springframework.http.ResponseEntity<ApiResponse<?>> conflict(RuntimeException error) {
        return org.springframework.http.ResponseEntity.status(409).body(ApiResponse.error(409,"State changed; reload the current revision"));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> missing() {
        return org.springframework.http.ResponseEntity.status(404).body(ApiResponse.error(404,"Binding not found"));
    }
}

