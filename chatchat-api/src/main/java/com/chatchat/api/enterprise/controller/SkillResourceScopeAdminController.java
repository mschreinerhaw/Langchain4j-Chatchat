package com.chatchat.api.enterprise.controller;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;

/** Maintains the resources an Agent Skill may request at runtime. */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/enterprise/skill-resource-scopes")
public class SkillResourceScopeAdminController {
    private static final Set<String> TYPES = Set.of("DOCUMENT", "KNOWLEDGE_BASE");
    private final SkillResourceScopeRepository repository;
    private final EnterpriseAdminService adminService;

    @GetMapping
    public ApiResponse<List<SkillResourceScope>> list(HttpServletRequest request,
            @RequestParam("tenantId") String tenantId, @RequestParam("skillId") String skillId) {
        requireTenant(request, tenantId);
        required(skillId, "skillId");
        return ApiResponse.success(repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(
            tenantId, skillId));
    }

    @PostMapping
    public ApiResponse<SkillResourceScope> create(HttpServletRequest request,
                                                   @RequestBody SkillResourceScope input) {
        validate(input);
        requireTenant(request, input.getTenantId());
        input.setId(null);
        return ApiResponse.success(repository.save(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<SkillResourceScope> update(HttpServletRequest request, @PathVariable("id") String id,
                                                   @RequestBody SkillResourceScope input) {
        SkillResourceScope stored = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Skill resource scope not found"));
        requireTenant(request, stored.getTenantId());
        validate(input);
        requireTenant(request, input.getTenantId());
        stored.setTenantId(input.getTenantId());
        stored.setSkillId(input.getSkillId());
        stored.setResourceType(input.getResourceType());
        stored.setResourceId(input.getResourceId());
        stored.setEnabled(input.isEnabled());
        return ApiResponse.success(repository.save(stored));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable("id") String id) {
        SkillResourceScope stored = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Skill resource scope not found"));
        requireTenant(request, stored.getTenantId());
        repository.delete(stored);
        return ApiResponse.success(null);
    }

    private void validate(SkillResourceScope input) {
        if (input == null) throw new IllegalArgumentException("Skill resource scope is required");
        required(input.getTenantId(), "tenantId");
        required(input.getSkillId(), "skillId");
        required(input.getResourceId(), "resourceId");
        required(input.getResourceType(), "resourceType");
        input.setResourceType(input.getResourceType().trim().toUpperCase(Locale.ROOT));
        if (!TYPES.contains(input.getResourceType()))
            throw new IllegalArgumentException("Unsupported resourceType");
        if ("KNOWLEDGE_BASE".equals(input.getResourceType()))
            input.setResourceId(input.getResourceId().trim().toLowerCase(Locale.ROOT));
    }

    private void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private void requireTenant(HttpServletRequest request, String tenantId) {
        Object userId = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        EnterpriseAdminService.UserView caller = adminService.getUserView(String.valueOf(userId));
        if (!tenantId.equals(caller.tenantId())
            && (caller.tenantNo() == null || caller.tenantNo() != PLATFORM_TENANT_NO))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-tenant Skill scope is forbidden");
    }
}
