package com.chatchat.enterprise.service;

import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.security.ResourceGrant;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.security.ResourceGrantRepository;
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
    private final ResourceGrantRepository grants;
    private final SysUserRepository users;
    private final SysUserRoleRepository userRoles;
    private final SysRoleRepository roles;

    @Override
    @Transactional(readOnly = true)
    public Set<String> allowedIds(String resourceType, String tenantId, String userId,
                                  Set<String> ignoredCallerRoles, Set<String> candidateIds) {
        return evaluate(resourceType, tenantId, userId, candidateIds, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> explicitlyAllowedIds(String resourceType, String tenantId, String userId,
                                             Set<String> ignoredCallerRoles, Set<String> candidateIds) {
        return evaluate(resourceType, tenantId, userId, candidateIds, true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasConfiguredRules(String resourceType, String tenantId) {
        return tenantId != null && resourceType != null
            && grants.existsByTenantIdAndResourceType(tenantId, resourceType);
    }

    private Set<String> evaluate(String resourceType, String tenantId, String userId,
                                 Set<String> candidateIds, boolean explicitOnly) {
        if (candidateIds == null || candidateIds.isEmpty()) return Set.of();
        if (tenantId == null || tenantId.isBlank() || resourceType == null || resourceType.isBlank()) return Set.of();
        SysUser user = userId == null ? null : users.findById(userId).orElse(null);
        boolean validUser = user != null && tenantId.equals(user.getTenantId())
            && "enabled".equalsIgnoreCase(user.getStatus());
        Set<String> activeRoleIds = new HashSet<>();
        if (validUser) {
            Set<String> assigned = new HashSet<>();
            userRoles.findByUserId(userId).stream()
                .filter(binding -> tenantId.equals(binding.getTenantId()))
                .forEach(binding -> assigned.add(binding.getRoleId()));
            for (SysRole role : assigned.isEmpty() ? List.<SysRole>of()
                : roles.findByTenantIdAndIdIn(tenantId, assigned)) {
                if (assigned.contains(role.getId()) && "enabled".equalsIgnoreCase(role.getStatus())) {
                    if ("super_admin".equalsIgnoreCase(role.getRoleCode())) return Set.copyOf(candidateIds);
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
        Map<String, List<ResourceGrant>> rulesById = new HashMap<>();
        for (ResourceGrant rule : rules) {
            rulesById.computeIfAbsent(rule.getResourceId(), ignored -> new ArrayList<>()).add(rule);
        }
        List<ResourceGrant> wildcardRules = rulesById.getOrDefault("*", List.of());
        Set<String> allowed = new LinkedHashSet<>();
        for (String id : candidateIds) {
            List<ResourceGrant> directRules = rulesById.getOrDefault(id, List.of());
            if (directRules.isEmpty() && wildcardRules.isEmpty()) {
                if (!explicitOnly) allowed.add(id);
                continue;
            }
            boolean deny = false;
            boolean allow = false;
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
