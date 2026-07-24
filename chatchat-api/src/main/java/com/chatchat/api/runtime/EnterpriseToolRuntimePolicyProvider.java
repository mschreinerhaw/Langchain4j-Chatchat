package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.ToolRuntimePolicy;
import com.chatchat.agents.runtime.ToolRuntimePolicyProvider;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.enterprise.entity.McpToolPermission;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.SysRole;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
import com.chatchat.enterprise.repository.McpToolPermissionRepository;
import com.chatchat.enterprise.repository.SysRoleRepository;
import com.chatchat.enterprise.repository.SysTenantRepository;
import com.chatchat.enterprise.repository.SysUserRepository;
import com.chatchat.enterprise.repository.SysUserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.time.Instant;

import static com.chatchat.common.constants.TenantConstants.PLATFORM_TENANT_NO;

@Component
@RequiredArgsConstructor
public class EnterpriseToolRuntimePolicyProvider implements ToolRuntimePolicyProvider {

    private final McpToolPermissionRepository toolPermissionRepository;
    private final McpToolAssetRepository toolAssetRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysUserRepository userRepository;
    private final SysTenantRepository tenantRepository;

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
        if (toolName == null) {
            return null;
        }
        if (!isManagedMcpTool(toolName, metadata)) {
            return null;
        }
        if (tenantId == null) {
            return denied("MCP asset authorization requires tenant and tool context");
        }

        String userId = normalize(request.getUserId());
        Set<String> roleIds = resolvedRoleIds(request, tenantId, userId);
        if (isAdminUser(userId) || hasRoleCode(roleIds, tenantId, "super_admin")) {
            return ToolRuntimePolicy.builder().allowed(true).build();
        }

        List<McpToolPermission> matched = new ArrayList<>();
        if (userId != null) {
            matched.addAll(toolPermissionRepository.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
                tenantId,
                "USER",
                userId
            ));
        }
        for (String role : roleIds) {
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
        matched = matched.stream().filter(this::active).distinct().toList();
        if (matched.isEmpty()) {
            return denied("No MCP asset authorization is assigned to caller");
        }

        RequestScope requestedScope = requestedScope(request, tenantId);
        List<McpToolPermission> effective = matched.stream()
            .filter(permission -> permissionMatches(permission, toolName, metadata, requestedScope))
            .toList();
        boolean hasAllowList = matched.stream().anyMatch(permission -> "allow".equalsIgnoreCase(permission.getEffect()));
        boolean denied = effective.stream().anyMatch(permission -> "deny".equalsIgnoreCase(permission.getEffect()));
        if (denied) {
            return denied("Tool denied by enterprise permission policy");
        }
        if (hasAllowList && effective.stream().noneMatch(permission -> "allow".equalsIgnoreCase(permission.getEffect()))) {
            return denied("Tool or asset not included in enterprise allow list");
        }
        if (!effective.isEmpty()) {
            return ToolRuntimePolicy.builder()
                .allowed(true)
                .build();
        }
        return denied("Tool or asset not authorized for caller");
    }

    private ToolRuntimePolicy denied(String reason) {
        return ToolRuntimePolicy.builder().allowed(false).reason(reason).build();
    }

    private boolean isManagedMcpTool(String toolName, ToolMetadata metadata) {
        if (toolAssetRepository.findByLocalToolName(toolName).isPresent()) {
            return true;
        }
        String metadataId = metadata == null ? null : normalize(metadata.getId());
        return metadataId != null && toolAssetRepository.findById(metadataId).isPresent();
    }

    private boolean active(McpToolPermission permission) {
        return permission != null && permission.isEnabled()
            && (permission.getExpiresAt() == null || permission.getExpiresAt().isAfter(Instant.now()));
    }

    private boolean permissionMatches(McpToolPermission permission,
                                      String toolName,
                                      ToolMetadata metadata,
                                      RequestScope requestedScope) {
        boolean toolMatched = toolMatches(permission, toolName, metadata);
        String configuredScope = normalizeText(permission.getScopeExpression());
        if (configuredScope == null) {
            return toolMatched;
        }
        if (toolMatched && (requestedScope == null || requestedScope.domain() == null)) {
            return true;
        }
        return scopeMatches(configuredScope, requestedScope);
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

    private Set<String> resolvedRoleIds(ToolRuntimeRequest request, String tenantId, String userId) {
        Set<String> roles = new LinkedHashSet<>();
        if (request == null) {
            return roles;
        }
        collectRoles(request.getAttributes(), roles);
        if (request.getToolInput() != null) {
            collectRoles(request.getToolInput().getContext(), roles);
        }
        if (userId != null) {
            userRoleRepository.findByUserId(userId).forEach(binding -> roles.add(normalize(binding.getRoleId())));
        }
        Map<String, String> codeToId = new LinkedHashMap<>();
        roleRepository.findByTenantIdOrderByRoleNameAsc(tenantId).forEach(role -> {
            String id = normalize(role.getId());
            String code = normalize(role.getRoleCode());
            if (id != null && code != null) {
                codeToId.put(code, id);
            }
        });
        new ArrayList<>(roles).forEach(role -> {
            String roleId = codeToId.get(role);
            if (roleId != null) {
                roles.add(roleId);
            }
        });
        roles.remove(null);
        return roles;
    }

    private boolean hasRoleCode(Set<String> roleIds, String tenantId, String expectedCode) {
        if (roleIds.contains(normalize(expectedCode))) {
            return true;
        }
        return roleRepository.findByTenantIdOrderByRoleNameAsc(tenantId).stream()
            .filter(role -> expectedCode.equals(normalize(role.getRoleCode())))
            .map(SysRole::getId)
            .map(this::normalize)
            .anyMatch(roleIds::contains);
    }

    private boolean isAdminUser(String userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
            .or(() -> userRepository.findByUsername(userId))
                .filter(user -> "admin".equalsIgnoreCase(user.getUsername()))
                .flatMap(user -> tenantRepository.findById(user.getTenantId()))
                .map(tenant -> tenant.getTenantNo() != null && tenant.getTenantNo() == PLATFORM_TENANT_NO)
            .orElse(false);
    }

    private RequestScope requestedScope(ToolRuntimeRequest request, String tenantId) {
        Map<String, Object> attributes = request.getAttributes();
        Map<String, Object> context = request.getToolInput() == null ? null : request.getToolInput().getContext();
        Map<String, Object> parameters = request.getToolInput() == null ? null : request.getToolInput().getParameters();
        String explicit = firstValue("scopeExpression", attributes, context, parameters);
        if (explicit != null) {
            RequestScope parsed = RequestScope.parse(explicit);
            if (parsed != null) {
                return parsed;
            }
        }
        return new RequestScope(
            normalize(firstValue("assetType", attributes, context, parameters)),
            normalize(firstValue("capability", attributes, context, parameters)),
            normalize(firstValue("action", attributes, context, parameters)),
            tenantId,
            firstValue("domain", attributes, context, parameters),
            normalize(firstValue("permissionLevel", attributes, context, parameters))
        );
    }

    private boolean scopeMatches(String configured, RequestScope requested) {
        RequestScope permission = RequestScope.parse(configured);
        return permission != null && requested != null
            && matchesPart(permission.assetType(), requested.assetType())
            && matchesPart(permission.capability(), requested.capability())
            && matchesPart(permission.action(), requested.action())
            && matchesPart(permission.tenantId(), requested.tenantId())
            && matchesPart(permission.domain(), requested.domain())
            && matchesPart(permission.level(), requested.level());
    }

    private boolean matchesPart(String configured, String requested) {
        return configured == null || "*".equals(configured) || configured.equals(requested);
    }

    @SafeVarargs
    private final String firstValue(String key, Map<String, Object>... sources) {
        for (Map<String, Object> source : sources) {
            if (source != null && source.get(key) != null) {
                String value = normalizeText(String.valueOf(source.get(key)));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
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

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RequestScope(String assetType,
                                String capability,
                                String action,
                                String tenantId,
                                String domain,
                                String level) {
        private static RequestScope parse(String expression) {
            if (expression == null || !expression.startsWith("mcp:")) {
                return null;
            }
            String[] sections = expression.split("@", 2);
            String[] head = sections[0].split(":");
            if (head.length < 4) {
                return null;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            if (sections.length > 1) {
                for (String item : sections[1].split(";")) {
                    String[] pair = item.split("=", 2);
                    if (pair.length == 2) {
                        attributes.put(normalizePart(pair[0]), textPart(pair[1]));
                    }
                }
            }
            return new RequestScope(
                normalizePart(head[1]),
                normalizePart(head[2]),
                normalizePart(head[3]),
                textPart(attributes.get("tenant")),
                textPart(attributes.get("domain")),
                normalizePart(attributes.get("level"))
            );
        }

        private static String normalizePart(String value) {
            String text = textPart(value);
            return text == null ? null : text.toLowerCase(Locale.ROOT);
        }

        private static String textPart(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
