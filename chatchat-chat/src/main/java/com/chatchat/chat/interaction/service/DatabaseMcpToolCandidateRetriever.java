package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.common.retrieval.AuthorizedRetrieval;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP database is the scope authority; semantic search only orders this scope. */
@Service
@RequiredArgsConstructor
public class DatabaseMcpToolCandidateRetriever implements McpToolCandidateRetriever {
    private final McpToolAssetRepository tools;
    private final McpToolPermissionRepository permissions;
    private final SysUserRepository users;
    private final SysUserRoleRepository userRoles;
    private final SysRoleRepository roles;
    private final McpToolSemanticIndex semanticIndex;
    private final ToolWorkflowContractCatalog contracts;

    @Autowired(required = false)
    private ResourceAuthorizationPort resourceAuthorization;

    @Override
    public Selection retrieve(InteractionRequest request, List<String> candidateNames, int limit) {
        if (request == null || blank(request.getTenantId()) || blank(request.getUserId())
            || candidateNames == null || candidateNames.isEmpty()) {
            return new Selection(Set.of(), Set.of(), List.of());
        }
        Set<String> candidateSet = new HashSet<>(candidateNames);
        List<McpToolAsset> catalog = tools.findByLocalToolNameInOrderByLocalToolNameAsc(candidateSet);
        Set<String> managed = new LinkedHashSet<>();
        catalog.forEach(tool -> managed.add(tool.getLocalToolName()));
        if (managed.isEmpty()) return new Selection(Set.of(), Set.of(), List.of());

        SysUser user = users.findById(request.getUserId()).orElse(null);
        if (user == null || !request.getTenantId().equals(user.getTenantId())
            || !"enabled".equalsIgnoreCase(user.getStatus())) {
            return new Selection(managed, Set.of(), List.of());
        }
        Map<String, SysRole> activeRoles = new LinkedHashMap<>();
        Set<String> assignedRoleIds = new LinkedHashSet<>();
        userRoles.findByUserId(user.getId()).stream()
            .filter(binding -> user.getTenantId().equals(binding.getTenantId()))
            .map(binding -> binding.getRoleId()).forEach(assignedRoleIds::add);
        (assignedRoleIds.isEmpty() ? List.<SysRole>of()
            : roles.findByTenantIdAndIdIn(user.getTenantId(), assignedRoleIds)).stream()
            .filter(role -> "enabled".equalsIgnoreCase(role.getStatus()))
            .forEach(role -> activeRoles.put(role.getId(), role));
        Set<String> roleIds = new LinkedHashSet<>();
        assignedRoleIds.stream()
            .filter(activeRoles::containsKey)
            .forEach(roleIds::add);
        List<McpToolPermission> grants = loadGrants(user, roleIds);
        List<McpToolPermission> activeGrants = grants.stream().filter(this::active).toList();

        List<McpToolAsset> nativeAllowed = catalog.stream()
            .filter(tool -> tool.isEnabled() && "online".equalsIgnoreCase(tool.getStatus()))
            .filter(tool -> permitted(tool, activeGrants))
            .toList();
        Set<String> grantAllowedIds = resourceAuthorization == null ? nativeAllowed.stream()
            .map(McpToolAsset::getLocalToolName).collect(java.util.stream.Collectors.toSet())
            : resourceAuthorization.allowedIds(ResourceAuthorizationPort.MCP_TOOL,
                user.getTenantId(), user.getId(), roleIds,
                nativeAllowed.stream().map(McpToolAsset::getLocalToolName)
                    .collect(java.util.stream.Collectors.toSet()));
        List<McpToolAsset> allowed = nativeAllowed.stream()
            .filter(tool -> grantAllowedIds.contains(tool.getLocalToolName())).toList();
        Set<String> allowedNames = new LinkedHashSet<>();
        allowed.forEach(tool -> allowedNames.add(tool.getLocalToolName()));
        List<McpToolPermission> finalGrants = loadGrants(user, roleIds).stream()
            .filter(this::active).toList();
        java.util.function.Predicate<String> stillAllowed = name -> tools.findByLocalToolName(name)
            .filter(tool -> tool.isEnabled() && "online".equalsIgnoreCase(tool.getStatus()))
            .filter(tool -> permitted(tool, finalGrants))
            .filter(tool -> resourceAuthorization == null
                || resourceAuthorization.allowedIds(ResourceAuthorizationPort.MCP_TOOL,
                    user.getTenantId(), user.getId(), roleIds, Set.of(tool.getLocalToolName()))
                    .contains(tool.getLocalToolName())).isPresent();
        Set<String> verifiedAllowedNames = allowedNames.stream().filter(stillAllowed)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> ranked = AuthorizedRetrieval.select(
            AuthorizedRetrieval.Scope.restricted(user.getTenantId(), user.getId(), roleIds,
                verifiedAllowedNames),
            ignored -> expandPublishedRelations(
                semanticIndex.rank(request.getQuery(), allowed, Math.max(1, limit)),
                allowed, Math.max(1, limit)),
            name -> name,
            stillAllowed,
            Math.max(1, limit));
        return new Selection(managed, verifiedAllowedNames, ranked);
    }

    private List<McpToolPermission> loadGrants(SysUser user, Set<String> roleIds) {
        List<McpToolPermission> grants = new ArrayList<>();
        grants.addAll(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            user.getTenantId(), "USER", user.getId()));
        for (String roleId : roleIds) {
            grants.addAll(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
                user.getTenantId(), "ROLE", roleId));
        }
        grants.addAll(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            user.getTenantId(), "TENANT", user.getTenantId()));
        return grants;
    }

    private List<String> expandPublishedRelations(List<String> ranked, List<McpToolAsset> allowed, int limit) {
        Map<String, McpToolAsset> byName = new LinkedHashMap<>();
        allowed.forEach(tool -> byName.put(tool.getLocalToolName(), tool));
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String name : ranked) {
            if (!byName.containsKey(name) || selected.size() >= limit) continue;
            addWithDependencies(name, byName, selected, new HashSet<>(), limit, 2);
        }
        return selected.stream().limit(limit).toList();
    }

    private void addWithDependencies(String name, Map<String, McpToolAsset> allowed,
                                     LinkedHashSet<String> selected, Set<String> visiting,
                                     int limit, int depth) {
        McpToolAsset tool = allowed.get(name);
        if (tool == null || selected.contains(name) || selected.size() >= limit || !visiting.add(name)) return;
        ToolWorkflowContractSnapshot contract = contracts.findActive(
            tool.getServiceId(), tool.getLocalToolName(), tool.getRemoteToolName()).orElse(null);
        if (contract != null && depth > 0) {
            for (String dependency : relationNames(relation(contract, "dependsOnTools", "depends_on_tools"))) {
                addWithDependencies(dependency, allowed, selected, visiting, limit, depth - 1);
            }
        }
        if (selected.size() < limit) selected.add(name);
        if (contract != null && selected.size() < limit) {
            for (String related : relationNames(relation(contract, "relatedTools", "related_tools"))) {
                if (allowed.containsKey(related) && selected.size() < limit) selected.add(related);
            }
        }
        visiting.remove(name);
    }

    private Object relation(ToolWorkflowContractSnapshot contract, String first, String second) {
        Object value = contract.extensions().get(first);
        return value == null ? contract.extensions().get(second) : value;
    }

    private List<String> relationNames(Object value) {
        if (value instanceof Iterable<?> items) {
            List<String> names = new ArrayList<>();
            for (Object item : items) if (item != null && !String.valueOf(item).isBlank()) {
                names.add(String.valueOf(item).trim());
            }
            return names;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private boolean permitted(McpToolAsset tool, List<McpToolPermission> grants) {
        boolean allow = false;
        for (McpToolPermission permission : grants) {
            boolean toolMatched = matches(tool, permission);
            if (!toolMatched) continue;
            // Scoped rules require invocation arguments and remain an over-approximation here.
            if ("deny".equalsIgnoreCase(permission.getEffect())
                && blank(permission.getScopeExpression())) return false;
            if ("allow".equalsIgnoreCase(permission.getEffect())) allow = true;
        }
        return allow;
    }

    private boolean matches(McpToolAsset tool, McpToolPermission permission) {
        String name = permission.getLocalToolName();
        if (!blank(name)) return "*".equals(name) || name.equalsIgnoreCase(tool.getLocalToolName());
        String toolId = permission.getToolId();
        return !blank(toolId) && ("*".equals(toolId) || toolId.equals(tool.getId()));
    }

    private boolean active(McpToolPermission permission) {
        return permission != null && permission.isEnabled()
            && (permission.getExpiresAt() == null || permission.getExpiresAt().isAfter(Instant.now()));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
