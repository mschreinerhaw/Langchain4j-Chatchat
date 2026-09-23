package com.chatchat.api.enterprise.controller;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;

/** Administrative API for tenant, role, and user resource grants. */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/enterprise/resource-grants")
public class ResourceGrantAdminController {
    private static final Set<String> RESOURCE_TYPES = Set.of(ResourceAuthorizationPort.KNOWLEDGE,
        ResourceAuthorizationPort.KNOWLEDGE_BASE, ResourceAuthorizationPort.MCP_TOOL,
        ResourceAuthorizationPort.SKILL, ResourceAuthorizationPort.AGENT_SKILL);
    private static final Set<String> PRINCIPAL_TYPES = Set.of("TENANT", "ROLE", "USER");
    private static final Set<String> EFFECTS = Set.of("ALLOW", "DENY");
    private final ResourceGrantRepository repository;
    private final EnterpriseAdminService adminService;

    @GetMapping
    public ApiResponse<List<ResourceGrant>> list(HttpServletRequest request,
                                                 @RequestParam("tenantId") String tenantId,
                                                 @RequestParam("resourceType") String resourceType) {
        require(tenantId, "tenantId");
        requireTenantAccess(request, tenantId);
        String kind = normalized(resourceType, RESOURCE_TYPES, "resourceType");
        return ApiResponse.success(repository.findByTenantIdAndResourceTypeOrderByUpdatedAtDesc(tenantId, kind));
    }

    @PostMapping
    public ApiResponse<ResourceGrant> create(HttpServletRequest request, @RequestBody ResourceGrant input) {
        validate(input);
        requireTenantAccess(request, input.getTenantId());
        input.setId(null);
        return ApiResponse.success(repository.save(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResourceGrant> update(HttpServletRequest request, @PathVariable("id") String id,
                                             @RequestBody ResourceGrant input) {
        ResourceGrant stored = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Resource grant not found"));
        requireTenantAccess(request, stored.getTenantId());
        validate(input);
        requireTenantAccess(request, input.getTenantId());
        stored.setTenantId(input.getTenantId());
        stored.setResourceType(input.getResourceType());
        stored.setResourceId(input.getResourceId());
        stored.setPrincipalType(input.getPrincipalType());
        stored.setPrincipalId(input.getPrincipalId());
        stored.setEffect(input.getEffect());
        stored.setEnabled(input.isEnabled());
        stored.setExpiresAt(input.getExpiresAt());
        return ApiResponse.success(repository.save(stored));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable("id") String id) {
        ResourceGrant stored = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Resource grant not found"));
        requireTenantAccess(request, stored.getTenantId());
        repository.delete(stored);
        return ApiResponse.success(null);
    }

    private void validate(ResourceGrant grant) {
        if (grant == null) throw new IllegalArgumentException("Resource grant is required");
        require(grant.getTenantId(), "tenantId");
        require(grant.getResourceId(), "resourceId");
        require(grant.getPrincipalId(), "principalId");
        grant.setResourceType(normalized(grant.getResourceType(), RESOURCE_TYPES, "resourceType"));
        if (ResourceAuthorizationPort.KNOWLEDGE_BASE.equals(grant.getResourceType())
            && !"*".equals(grant.getResourceId())) {
            grant.setResourceId(grant.getResourceId().trim().toLowerCase(java.util.Locale.ROOT));
        }
        grant.setPrincipalType(normalized(grant.getPrincipalType(), PRINCIPAL_TYPES, "principalType"));
        grant.setEffect(normalized(grant.getEffect(), EFFECTS, "effect"));
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private void requireTenantAccess(HttpServletRequest request, String tenantId) {
        Object userId = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        EnterpriseAdminService.UserView caller = adminService.getUserView(String.valueOf(userId));
        if (!tenantId.equals(caller.tenantId())
            && (caller.tenantNo() == null || caller.tenantNo() != PLATFORM_TENANT_NO)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-tenant resource grants are forbidden");
        }
    }

    private String normalized(String value, Set<String> allowed, String field) {
        require(value, field);
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Unsupported " + field);
        return normalized;
    }
}
