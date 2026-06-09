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

@Component
@RequiredArgsConstructor
public class EnterpriseToolRuntimePolicyProvider implements ToolRuntimePolicyProvider {

    private final McpToolPermissionRepository toolPermissionRepository;

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
                "user",
                userId
            ));
        }
        matched.addAll(toolPermissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            tenantId,
            "tenant",
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

    private boolean toolMatches(McpToolPermission permission, String toolName, ToolMetadata metadata) {
        String localToolName = normalize(permission.getLocalToolName());
        if (localToolName != null) {
            return toolName.equals(localToolName);
        }
        String toolId = normalize(permission.getToolId());
        if (toolId != null && metadata != null && metadata.getId() != null) {
            return toolId.equals(normalize(metadata.getId()));
        }
        return false;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
