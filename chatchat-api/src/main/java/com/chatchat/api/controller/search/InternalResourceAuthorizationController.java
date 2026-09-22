package com.chatchat.api.controller.search;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.ResourceAuthorizationRequest;
import com.chatchat.common.retrieval.ResourceAuthorizationResult;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** Rechecks candidate IDs against current PostgreSQL grants for the MCP document service. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/resource-authorization")
public class InternalResourceAuthorizationController {
    private static final int MAX_CANDIDATES = 500;
    private final ResourceAuthorizationPort authorization;
    private final EnterpriseAdminService adminService;

    @PostMapping
    public ApiResponse<ResourceAuthorizationResult> authorize(@RequestBody ResourceAuthorizationRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()
            || request.userId() == null || request.userId().isBlank()
            || request.resourceType() == null || request.resourceType().isBlank()) {
            return ApiResponse.badRequest("tenantId, userId and resourceType are required");
        }
        if (!Set.of(ResourceAuthorizationPort.KNOWLEDGE, ResourceAuthorizationPort.KNOWLEDGE_BASE,
            ResourceAuthorizationPort.MCP_TOOL, ResourceAuthorizationPort.SKILL,
            ResourceAuthorizationPort.AGENT_SKILL).contains(request.resourceType())) {
            return ApiResponse.badRequest("Unsupported resourceType");
        }
        if (request.candidateIds().size() > MAX_CANDIDATES || request.candidateIds().stream()
            .anyMatch(id -> id == null || id.isBlank())) {
            return ApiResponse.badRequest("Invalid candidateIds");
        }
        EnterpriseAdminService.UserView user;
        try {
            user = adminService.getUserView(request.userId());
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(403, "Resource authorization principal is not authorized");
        }
        if (user == null || !"enabled".equalsIgnoreCase(user.status())
            || !Objects.equals(user.tenantId(), request.tenantId())) {
            return ApiResponse.error(403, "Resource authorization principal is not authorized");
        }
        return ApiResponse.success(new ResourceAuthorizationResult(authorization.allowedIds(
            request.resourceType(), user.tenantId(), user.id(),
            user.roleIds() == null ? Set.of() : Set.copyOf(user.roleIds()),
            request.candidateIds())));
    }
}
