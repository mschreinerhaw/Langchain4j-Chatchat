package com.chatchat.enterprise.service;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
import com.chatchat.enterprise.repository.security.RoleAgentBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Shared RBAC overlay. Domain ACLs are intersected with this result. */
@Service
@RequiredArgsConstructor
public class ResourceAuthorizationService implements ResourceAuthorizationPort {
    @Override @Transactional(readOnly=true)
    public Set<String> readableMetadataIds(String tenantId,String userId,Set<String> candidates){
        return evaluate(MCP_TOOL,tenantId,userId,candidates,null,true);
    }
    private final ResourceGrantRepository grants;
    private final SysUserRepository users;
    private final SysUserRoleRepository userRoles;
    private final SysRoleRepository roles;
    private final RoleAgentBindingRepository roleAgents;
    private final com.chatchat.enterprise.repository.identity.SysRolePermissionRepository rolePermissions;
    private final com.chatchat.enterprise.repository.identity.SysPermissionRepository permissions;

    @Override
    @Transactional(readOnly = true)
    public Set<String> allowedIds(String resourceType, String tenantId, String userId,
                                  Set<String> ignoredCallerRoles, Set<String> candidateIds) {
        return evaluate(resourceType, tenantId, userId, candidateIds, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> explicitlyAllowedIds(String resourceType, String tenantId, String userId,
                                             Set<String> ignoredCallerRoles, Set<String> candidateIds) {
        return evaluate(resourceType, tenantId, userId, candidateIds, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> allowedIdsForAgent(String type, String tenantId, String userId,
                                          Set<String> ignoredCallerRoles, Set<String> candidates, String agentId) {
        return evaluate(type, tenantId, userId, candidates, agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> explicitlyAllowedIdsForAgent(String type, String tenantId, String userId,
                                                    Set<String> ignoredCallerRoles, Set<String> candidates, String agentId) {
        return evaluate(type, tenantId, userId, candidates, agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasConfiguredRules(String resourceType, String tenantId) {
        return tenantId != null && resourceType != null
            && grants.existsByTenantIdAndResourceType(tenantId, resourceType);
    }

    private Set<String> evaluate(String resourceType, String tenantId, String userId,
                                 Set<String> candidateIds, String agentId) {
        return evaluate(resourceType,tenantId,userId,candidateIds,agentId,false);
    }
    private Set<String> evaluate(String resourceType,String tenantId,String userId,
                                 Set<String> candidateIds,String agentId,boolean catalogDefault){
        if (candidateIds == null || candidateIds.isEmpty()) return Set.of();
        if (tenantId == null || tenantId.isBlank() || resourceType == null || resourceType.isBlank()) return Set.of();
        SysUser user = userId == null ? null : users.findById(userId).orElse(null);
        boolean validUser = user != null && tenantId.equals(user.getTenantId())
            && "enabled".equalsIgnoreCase(user.getStatus());
        if(catalogDefault&&!validUser)return Set.of();
        Set<String> activeRoleIds = new HashSet<>();
        if (validUser) {
            Set<String> assigned = new HashSet<>();
            userRoles.findByUserId(userId).stream()
                .filter(binding -> tenantId.equals(binding.getTenantId()))
                .forEach(binding -> assigned.add(binding.getRoleId()));
            for (SysRole role : assigned.isEmpty() ? List.<SysRole>of()
                : roles.findByTenantIdAndIdIn(tenantId, assigned)) {
                if (assigned.contains(role.getId()) && "enabled".equalsIgnoreCase(role.getStatus())) {
                    activeRoleIds.add(role.getId());
                }
            }
        }
        List<String> lookupIds = new ArrayList<>(candidateIds);
        lookupIds.add("*");
        List<ResourceGrant> rules = new ArrayList<>();
        for (int offset = 0; offset < lookupIds.size(); offset += 500) {
            rules.addAll(grants.findByTenantIdAndResourceTypeAndResourceIdIn(
                tenantId, resourceType, lookupIds.subList(offset, Math.min(offset + 500, lookupIds.size()))));
        }
        Instant now = Instant.now();
        if (AGENT_SKILL.equals(resourceType) && validUser && !activeRoleIds.isEmpty()) {
            // Existing role bindings are persisted explicit Agent grants, including at execution time.
            // Merge them into this evaluator so the runtime and UI share the same deny/expiry rules.
            roleAgents.findByRoleIdIn(new ArrayList<>(activeRoleIds)).stream()
                .filter(binding -> tenantId.equals(binding.getTenantId()) && activeRoleIds.contains(binding.getRoleId())
                    && binding.isEnabled() && binding.getAgentId() != null && candidateIds.contains(binding.getAgentId().trim())
                    && (binding.getEffectiveTime() == null || !binding.getEffectiveTime().isAfter(now))
                    && (binding.getExpireTime() == null || binding.getExpireTime().isAfter(now)))
                .forEach(binding -> rules.add(agentAllow(tenantId, binding.getRoleId(), binding.getAgentId().trim())));
            permissions.findByPermissionCode("platform:agents:all")
                .filter(permission -> "enabled".equalsIgnoreCase(permission.getStatus()))
                .ifPresent(permission -> rolePermissions.findByRoleIdIn(new ArrayList<>(activeRoleIds)).stream()
                    .filter(link -> tenantId.equals(link.getTenantId()) && activeRoleIds.contains(link.getRoleId())
                        && permission.getId().equals(link.getPermissionId()))
                    .forEach(link -> rules.add(agentAllow(tenantId, link.getRoleId(), "*"))));
        }
        Set<String> agentRoleIds = new HashSet<>();
        if (agentId != null && !agentId.isBlank() && !activeRoleIds.isEmpty()) {
            roleAgents.findByRoleIdIn(new ArrayList<>(activeRoleIds)).stream()
                .filter(binding -> tenantId.equals(binding.getTenantId()) && binding.isEnabled()
                    && agentId.equals(binding.getAgentId())
                    && (binding.getEffectiveTime() == null || !binding.getEffectiveTime().isAfter(now))
                    && (binding.getExpireTime() == null || binding.getExpireTime().isAfter(now)))
                .forEach(binding -> agentRoleIds.add(binding.getRoleId()));
        }
        Map<String, List<ResourceGrant>> rulesById = new HashMap<>();
        for (ResourceGrant rule : rules) {
            if (rule.getAgentId() != null && !rule.getAgentId().isBlank()
                && (!rule.getAgentId().equals(agentId) || !"ROLE".equals(rule.getPrincipalType())
                    || !agentRoleIds.contains(rule.getPrincipalId()))) continue;
            rulesById.computeIfAbsent(rule.getResourceId(), ignored -> new ArrayList<>()).add(rule);
        }
        List<ResourceGrant> wildcardRules = rulesById.getOrDefault("*", List.of());
        Set<String> allowed = new LinkedHashSet<>();
        for (String id : candidateIds) {
            List<ResourceGrant> directRules = rulesById.getOrDefault(id, List.of());
            if (directRules.isEmpty() && wildcardRules.isEmpty()&&!catalogDefault) continue;
            boolean deny = false;
            boolean allow = catalogDefault&&validUser;
            for (ResourceGrant rule : directRules) {
                if (!active(rule, now) || !principalMatches(rule, tenantId, userId, validUser, activeRoleIds)) continue;
                deny |= "DENY".equalsIgnoreCase(rule.getEffect());
                allow |= "ALLOW".equalsIgnoreCase(rule.getEffect());
            }
            for (ResourceGrant rule : wildcardRules) {
                if (!active(rule, now) || !principalMatches(rule, tenantId, userId, validUser, activeRoleIds)) continue;
                deny |= "DENY".equalsIgnoreCase(rule.getEffect());
                allow |= "ALLOW".equalsIgnoreCase(rule.getEffect());
            }
            if (!deny && allow) allowed.add(id);
        }
        return allowed;
    }

    private boolean active(ResourceGrant rule, Instant now) {
        return rule.isEnabled() && (rule.getExpiresAt() == null || rule.getExpiresAt().isAfter(now));
    }

    private ResourceGrant agentAllow(String tenantId, String roleId, String agentId) {
        ResourceGrant rule = new ResourceGrant(); rule.setTenantId(tenantId); rule.setResourceType(AGENT_SKILL);
        rule.setResourceId(agentId); rule.setPrincipalType("ROLE"); rule.setPrincipalId(roleId);
        rule.setEffect("ALLOW"); rule.setEnabled(true); return rule;
    }

    private boolean principalMatches(ResourceGrant rule, String tenantId, String userId,
                                     boolean validUser, Set<String> roleIds) {
        return switch (rule.getPrincipalType().toUpperCase(java.util.Locale.ROOT)) {
            case "TENANT" -> tenantId.equals(rule.getPrincipalId());
            case "USER" -> validUser && userId.equals(rule.getPrincipalId());
            case "ROLE" -> validUser && roleIds.contains(rule.getPrincipalId());
            default -> false;
        };
    }
}
