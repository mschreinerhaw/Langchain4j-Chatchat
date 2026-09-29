package com.chatchat.api.enterprise.controller;

import com.chatchat.api.enterprise.service.SkillRoleQueryService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/enterprise/resource-grants/skill-role-query")
public class SkillRoleQueryController {
    private final SkillRoleQueryService queryService;
    private final EnterpriseAdminService admin;

    @GetMapping
    public ApiResponse<SkillRoleQueryService.Result> query(HttpServletRequest request,
        @RequestParam("tenantId") String tenantId,
        @RequestParam(value = "view", defaultValue = "roles") String view,
        @RequestParam(value = "query", defaultValue = "") String query,
        @RequestParam(value = "resourceType", defaultValue = "") String type,
        @RequestParam(value = "roleId", defaultValue = "") String roleId,
        @RequestParam(value = "skillId", defaultValue = "") String skillId,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int size) {
        Object userId = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var caller = admin.getUserView(String.valueOf(userId));
        if (!tenantId.equals(caller.tenantId()) && (caller.tenantNo() == null || caller.tenantNo() != PLATFORM_TENANT_NO))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-tenant authorization query is forbidden");
        return ApiResponse.success(queryService.query(tenantId, view, query, type, roleId, skillId, page, size));
    }
}
