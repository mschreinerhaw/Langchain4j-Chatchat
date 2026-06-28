package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.ToolRuntimePolicy;
import com.chatchat.agents.runtime.ToolRuntimePolicyProvider;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

@Component
@RequiredArgsConstructor
public class EnterpriseToolRuntimePolicyProvider implements ToolRuntimePolicyProvider {

    private final McpToolPermissionRepository toolPermissionRepository;

    /**
     * Resolves the resolve.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @return the resolved resolve
     */
    @Override
    public ToolRuntimePolicy resolve(ToolRuntimeRequest request, ToolMetadata metadata) {
        String tenantId = normalize(request == null ? null : request.getTenantId());
        String toolName = normalize(request == null ? null : request.getToolName());
        if (tenantId == null || toolName == null) {
            return null;
        }

        List<McpToolPermission> matched = new ArrayList<>();
        String userId = normalize(request.getUserId());
        if (userId != null) {
            matched.addAll(toolPermissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
                tenantId,
                "USER",
                userId
            ));
        }
        for (String role : roleIds(request)) {
            matched.addAll(toolPermissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
                tenantId,
                "ROLE",
                role
            ));
        }
        matched.addAll(toolPermissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            tenantId,
            "TENANT",
            tenantId
        ));
        if (matched.isEmpty()) {
            return null;
        }

        List<McpToolPermission> effective = matched.stream()
            .filter(permission -> toolMatches(permission, toolName, metadata))
            .toList();
        boolean hasAllowList = matched.stream().anyMatch(permission -> "allow".equalsIgnoreCase(permission.getEffect()));
        boolean denied = effective.stream().anyMatch(permission -> "deny".equalsIgnoreCase(permission.getEffect()));
        if (denied) {
            return ToolRuntimePolicy.builder()
                .allowed(false)
                .reason("Tool denied by enterprise permission policy")
                .build();
        }
        if (hasAllowList && effective.stream().noneMatch(permission -> "allow".equalsIgnoreCase(permission.getEffect()))) {
            return ToolRuntimePolicy.builder()
                .allowed(false)
                .reason("Tool not included in enterprise allow list")
                .build();
        }
        if (!effective.isEmpty()) {
            return ToolRuntimePolicy.builder()
                .allowed(true)
                .build();
        }
        return null;
    }

    /**
     * Returns whether tool matches.
     *
     * @param permission the permission value
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return whether the condition is satisfied
     */
    private boolean toolMatches(McpToolPermission permission, String toolName, ToolMetadata metadata) {
        String localToolName = normalize(permission.getLocalToolName());
        if (localToolName != null) {
            return "*".equals(localToolName) || toolName.equals(localToolName);
        }
        String toolId = normalize(permission.getToolId());
        if (toolId != null && metadata != null && metadata.getId() != null) {
            return "*".equals(toolId) || toolId.equals(normalize(metadata.getId()));
        }
        return false;
    }

    private Set<String> roleIds(ToolRuntimeRequest request) {
        Set<String> roles = new LinkedHashSet<>();
        if (request == null) {
            return roles;
        }
        collectRoles(request.getAttributes(), roles);
        if (request.getToolInput() != null) {
            collectRoles(request.getToolInput().getContext(), roles);
        }
        roles.remove(null);
        return roles;
    }

    private void collectRoles(Map<String, Object> values, Set<String> roles) {
        if (values == null || values.isEmpty() || roles == null) {
            return;
        }
        collectRoleValue(values.get("roles"), roles);
        collectRoleValue(values.get("role"), roles);
        collectRoleValue(values.get("roleIds"), roles);
        collectRoleValue(values.get("role_ids"), roles);
    }

    private void collectRoleValue(Object value, Set<String> roles) {
        if (value == null || roles == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectRoleValue(item, roles);
            }
            return;
        }
        String text = String.valueOf(value);
        for (String item : text.split(",")) {
            String normalized = normalize(item);
            if (normalized != null) {
                roles.add(normalized);
            }
        }
    }

    /**
     * Normalizes the normalize.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
