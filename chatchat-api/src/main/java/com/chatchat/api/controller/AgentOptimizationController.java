package com.chatchat.api.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.task.learning.AgentOptimizationService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent-optimizations")
@Tag(name = "Agent Optimization", description = "Governed feedback-to-change proposals")
public class AgentOptimizationController {

    private final AgentOptimizationService service;

    @PostMapping
    @Operation(summary = "Create a non-executing optimization proposal from feedback evidence")
    public ApiResponse<AgentOptimizationService.ProposalView> propose(
        @RequestBody ProposalRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.propose(new AgentOptimizationService.ProposeCommand(
            tenant(request), body.agentId(), body.proposalType(), body.sourceExperienceIds(),
            body.patch(), body.evidence(), actor(request))));
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "Attach a passing regression report and validate the proposal")
    public ApiResponse<AgentOptimizationService.ProposalView> validate(
        @PathVariable("id") String id, @RequestBody Map<String, Object> report,
        HttpServletRequest request) {
        return ApiResponse.success(service.validate(tenant(request), id, report));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<AgentOptimizationService.ProposalView> approve(
        @PathVariable("id") String id, HttpServletRequest request) {
        return ApiResponse.success(service.approve(tenant(request), id, actor(request)));
    }

    @PostMapping("/{id}/canary")
    public ApiResponse<AgentOptimizationService.ProposalView> canary(
        @PathVariable("id") String id, @RequestParam("percent") int percent, HttpServletRequest request) {
        return ApiResponse.success(service.startCanary(tenant(request), id, percent));
    }

    @PostMapping("/{id}/canary/complete")
    public ApiResponse<AgentOptimizationService.ProposalView> completeCanary(
        @PathVariable("id") String id, @RequestBody Map<String, Object> metrics, HttpServletRequest request) {
        return ApiResponse.success(service.completeCanary(tenant(request), id, metrics));
    }

    @PostMapping("/{id}/rollout")
    public ApiResponse<AgentOptimizationService.ProposalView> rollout(
        @PathVariable("id") String id, HttpServletRequest request) {
        return ApiResponse.success(service.markRolledOut(tenant(request), id, actor(request)));
    }

    @GetMapping
    public ApiResponse<List<AgentOptimizationService.ProposalView>> list(
        @RequestParam("agentId") String agentId, HttpServletRequest request) {
        return ApiResponse.success(service.list(tenant(request), agentId));
    }

    private String tenant(HttpServletRequest request) {
        return attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID, "default");
    }

    private String actor(HttpServletRequest request) {
        return attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME,
            attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID, "system"));
    }

    private String attribute(HttpServletRequest request, String key, String fallback) {
        Object value = request == null ? null : request.getAttribute(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public record ProposalRequest(String agentId, String proposalType, List<String> sourceExperienceIds,
                                  Map<String, Object> patch, Map<String, Object> evidence) { }
}
